package com.school.ontology.core.model;

import com.school.ontology.common.Resp;

/**
 * 两份 overlay 共用的「草稿版本」算法（C 档对象类型在线编辑）。
 *
 * <p>版本必须回答同一个问题：「重启后实际生效的三份文本是什么」。
 * 因此对象 / 关系各自有 overlay 就用 overlay 文本，没有就用打包基线原文，
 * 再按 loader 完全相同的顺序拼 {@code object + "\n" + relation + "\n" + source} 求 SHA-256。
 * 任何一处调用走偏（例如始终用 loader 当前文本）都会出现「草稿版本算错 → 应用后 noChange」
 * 或「恢复基线应用不了」的问题。</p>
 */
public final class DefinitionDraftHash {

    private DefinitionDraftHash() {
    }

    /** 是否存在任一 overlay（对象或关系）。 */
    public static boolean hasDraft(DefinitionFileStore objectStore, DefinitionFileStore relationStore) {
        return (objectStore != null && objectStore.overlayExists())
                || (relationStore != null && relationStore.overlayExists());
    }

    /** 重启后会生效的三份文本对应的 hash（不是 "sha256:" 前缀形式）。 */
    public static String hash(ModelDefinitionLoader loader, DefinitionFileStore objectStore,
                              DefinitionFileStore relationStore) {
        String objectText = text(objectStore, loader.baselineObjectText());
        String relationText = text(relationStore, loader.baselineRelationText());
        return Resp.sha256(objectText + "\n" + relationText + "\n" + loader.sourceText());
    }

    /** 草稿 / 恢复基线的目标 mappingVersion。 */
    public static String version(String sourceId, ModelDefinitionLoader loader,
                                 DefinitionFileStore objectStore, DefinitionFileStore relationStore) {
        String hash = hash(loader, objectStore, relationStore);
        return sourceId + ":" + hash.substring(0, 12);
    }

    /** 指定内容作为对象 overlay 时的目标 mappingVersion（保存前算版本用）。 */
    public static String versionForObjectText(String sourceId, ModelDefinitionLoader loader,
                                              String candidateObjectText, DefinitionFileStore relationStore) {
        String relationText = text(relationStore, loader.baselineRelationText());
        String hash = Resp.sha256(candidateObjectText + "\n" + relationText + "\n" + loader.sourceText());
        return sourceId + ":" + hash.substring(0, 12);
    }

    /** 指定内容作为关系 overlay 时的目标 mappingVersion（保存前算版本用）。 */
    public static String versionForRelationText(String sourceId, ModelDefinitionLoader loader,
                                                String candidateRelationText,
                                                DefinitionFileStore objectStore) {
        String objectText = text(objectStore, loader.baselineObjectText());
        String hash = Resp.sha256(objectText + "\n" + candidateRelationText + "\n" + loader.sourceText());
        return sourceId + ":" + hash.substring(0, 12);
    }

    /** 指定内容作为关系 overlay 时的完整 definitionHash（带 sha256: 前缀）。 */
    public static String definitionHashForRelationText(ModelDefinitionLoader loader,
                                                       String candidateRelationText,
                                                       DefinitionFileStore objectStore) {
        String objectText = text(objectStore, loader.baselineObjectText());
        return "sha256:" + Resp.sha256(objectText + "\n" + candidateRelationText + "\n" + loader.sourceText());
    }

    /** 指定内容作为对象 overlay 时的完整 definitionHash（带 sha256: 前缀）。 */
    public static String definitionHashForObjectText(ModelDefinitionLoader loader,
                                                     String candidateObjectText,
                                                     DefinitionFileStore relationStore) {
        String relationText = text(relationStore, loader.baselineRelationText());
        return "sha256:" + Resp.sha256(candidateObjectText + "\n" + relationText + "\n" + loader.sourceText());
    }

    private static String text(DefinitionFileStore store, String baseline) {
        return store != null && store.overlayExists() ? store.readOverlayText() : baseline;
    }
}
