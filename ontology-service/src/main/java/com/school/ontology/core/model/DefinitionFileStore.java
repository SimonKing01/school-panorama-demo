package com.school.ontology.core.model;

import com.school.ontology.common.ApiException;
import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * overlay 定义文件层（07 增量设计 §4）。
 *
 * <p>overlay 是一份**完整**的 YAML 定义，落
 * `${ontology.definition-dir}/&lt;fileName&gt;`，属于运行时数据，不进版本库。
 * 本类只负责「文件怎么安全地读写」，不关心对象 / 关系类型的字段语义。</p>
 *
 * <p>同一个类现在服务两份 overlay：{@link #OVERLAY_FILE}（关系类型）与
 * {@link #OBJECT_OVERLAY_FILE}（对象类型），由 Spring 注册两个带 qualifier 的 Bean，
 * 各自拥有独立的锁、备份序列与回退留痕。单元测试仍可用旧的单参构造函数，
 * 默认即关系 overlay，行为与改造前逐字一致。</p>
 *
 * <p>三条保证：① 写入原子（tmp + ATOMIC_MOVE，中途失败不留半文件）；
 * ② 写前备份、备份只留最近 {@value #KEEP_BACKUPS} 份；③ 进程内 {@link ReentrantLock} +
 * 跨进程 {@link FileLock} 串行化，拿不到锁抛 409 {@code DEFINITION_LOCKED}。</p>
 */
public class DefinitionFileStore {

    /** 关系类型 overlay 文件名（保留原名，既有测试与线上文件均依赖它）。 */
    public static final String OVERLAY_FILE = "relation-types.yaml";
    /** 对象类型 overlay 文件名（C 档新增，与 classpath 的 object-types.yaml 同名）。 */
    public static final String OBJECT_OVERLAY_FILE = "object-types.yaml";
    private static final int KEEP_BACKUPS = 5;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Logger log = LoggerFactory.getLogger(DefinitionFileStore.class);

    private final Path directory;
    /** 本实例负责的 overlay 文件名；所有路径 / 备份 / 日志都基于它。 */
    private final String fileName;
    /** 出错时写 ont_error_log；单元测试构造时可为 null（只打 WARN，不碰数据库）。 */
    private final OntologyRepository repository;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Fallback lastFallback = Fallback.none();

    /** Spring 通过 {@code DefinitionFileStoreConfig} 的两个 @Bean 方法构造本类。 */
    public DefinitionFileStore(@Value("${ontology.definition-dir:/app/ontology-data}") String directory,
                               ObjectProvider<OntologyRepository> repositoryProvider) {
        this(Paths.get(directory == null || directory.isBlank() ? "/app/ontology-data" : directory),
                repositoryProvider == null ? null : repositoryProvider.getIfAvailable(), OVERLAY_FILE);
    }

    /** 单元测试用：只指定目录，默认关系 overlay，不依赖数据库。 */
    public DefinitionFileStore(Path directory) {
        this(directory, null, OVERLAY_FILE);
    }

    /** 指定目录与文件名（对象 overlay 用）；不依赖数据库。 */
    public DefinitionFileStore(Path directory, String fileName) {
        this(directory, null, fileName);
    }

    /** 完整构造：Spring 配置类与需要写错误日志的场景使用。 */
    public DefinitionFileStore(Path directory, OntologyRepository repository, String fileName) {
        this.directory = directory;
        this.repository = repository;
        this.fileName = fileName == null || fileName.isBlank() ? OVERLAY_FILE : fileName;
    }

    public Path directory() {
        return directory;
    }

    /** 本实例负责的 overlay 文件名。 */
    public String fileName() {
        return fileName;
    }

    public Path overlayPath() {
        return directory.resolve(fileName);
    }

    public boolean overlayExists() {
        return Files.isRegularFile(overlayPath());
    }

    /** 读取 overlay 原文；调用方保证 {@link #overlayExists()} 为 true。 */
    public String readOverlayText() {
        try {
            return new String(Files.readAllBytes(overlayPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取在线定义文件失败：" + e.getMessage(), e);
        }
    }

    /**
     * 原子写入 overlay：备份当前文件 → 写临时文件 → 原子替换 → 清理多余备份。
     *
     * <p>原子替换保证「要么是旧内容、要么是新内容」，绝不会出现半份 YAML 被下次启动读到。</p>
     */
    public void writeOverlay(String text) {
        lock.lock();
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(lockPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock fileLock = channel.tryLock()) {
                if (fileLock == null) {
                    throw new ApiException(409, "DEFINITION_LOCKED",
                            "定义文件正被其它进程写入，请稍后重试");
                }
                backupCurrent();
                Path tmp = directory.resolve(fileName + ".tmp." + System.nanoTime());
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tmp, overlayPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // 个别文件系统不支持原子移动：退化为普通替换，内容仍完整（tmp 已写完）。
                    Files.move(tmp, overlayPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                pruneBackups();
            } catch (IOException e) {
                throw new IllegalStateException("写入在线定义失败：" + e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("准备在线定义目录失败：" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** 删除 overlay（恢复基线）；返回是否确实删除了一份文件。 */
    public boolean deleteOverlay() {
        lock.lock();
        try {
            if (!overlayExists()) {
                return false;
            }
            backupCurrent();
            Files.deleteIfExists(overlayPath());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("删除在线定义失败：" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 隔离非法 overlay：改名 {@code .invalid.&lt;ts&gt;} 并记录回退原因。
     *
     * <p>改名而不是删除：坏文件是排查证据，但绝不能留在原位让下次启动再踩一次。</p>
     */
    public Path quarantineInvalidOverlay(String reason) {
        lock.lock();
        try {
            recordFallback(reason);
            if (!overlayExists()) {
                return null;
            }
            Path target = directory.resolve(fileName + ".invalid." + System.currentTimeMillis());
            Files.move(overlayPath(), target, StandardCopyOption.REPLACE_EXISTING);
            log.warn("在线定义 {} 非法，已隔离为 {}：{}", fileName, target.getFileName(), reason);
            return target;
        } catch (IOException e) {
            log.warn("隔离非法在线定义失败（不影响回退基线）：{}", e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 记录一次「overlay 非法 → 回退基线」：WARN 日志 + {@code ont_error_log(phase=OVERLAY_FALLBACK)}。
     *
     * <p>写库是尽力而为：本体库尚未就绪时只打日志，绝不让回退路径本身失败（否则就锁死服务了）。</p>
     */
    public void recordFallback(String reason) {
        String text = reason == null || reason.isBlank() ? "overlay 非法（原因未知）" : reason;
        this.lastFallback = new Fallback(true, text, LocalDateTime.now(), fileName);
        log.warn("在线定义 {} 校验未通过，已回退打包基线：{}", fileName, text);
        if (repository == null) {
            return;
        }
        try {
            repository.appendErrorLog("OVERLAY_FALLBACK", fileName, text);
        } catch (Exception e) {
            log.warn("回退事件未能写入 ont_error_log（本体库可能尚未就绪）：{}", e.getMessage());
        }
    }

    /** 本次进程生命周期内最近一次回退信息（供 {@code GET /model/versions} 的 lastFallback）。 */
    public Fallback lastFallback() {
        return lastFallback;
    }

    private Path lockPath() {
        return directory.resolve(fileName + ".lock");
    }

    private void backupCurrent() throws IOException {
        if (!overlayExists()) {
            return;
        }
        Path backup = directory.resolve(fileName + ".bak." + LocalDateTime.now().format(STAMP));
        int seq = 1;
        while (Files.exists(backup)) {
            backup = directory.resolve(fileName + ".bak."
                    + LocalDateTime.now().format(STAMP) + "-" + seq++);
        }
        Files.copy(overlayPath(), backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private void pruneBackups() throws IOException {
        List<Path> backups = backups();
        for (int i = KEEP_BACKUPS; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    /** 备份文件（按名字倒序，最新的在前）。 */
    List<Path> backups() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().startsWith(fileName + ".bak."))
                    .forEach(result::add);
        }
        result.sort(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));
        return result;
    }

    /** 回退信息（无回退时 occurred=false）。fileName 供管理页提示是哪份 overlay 被隔离。 */
    public record Fallback(boolean occurred, String reason, LocalDateTime at, String fileName) {

        static Fallback none() {
            return new Fallback(false, null, null, null);
        }

        /** 兼容旧调用点（两参 / 三参记录构造）。 */
        public Fallback(boolean occurred, String reason, LocalDateTime at) {
            this(occurred, reason, at, null);
        }
    }
}
