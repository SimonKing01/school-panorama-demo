package com.school.panorama.service;

import com.school.panorama.entity.Classroom;
import com.school.panorama.entity.Clazz;
import com.school.panorama.entity.Course;
import com.school.panorama.entity.School;
import com.school.panorama.entity.Student;
import com.school.panorama.entity.Teacher;
import com.school.panorama.mapper.ClassroomMapper;
import com.school.panorama.mapper.ClazzMapper;
import com.school.panorama.mapper.CourseMapper;
import com.school.panorama.mapper.SchoolMapper;
import com.school.panorama.mapper.StudentMapper;
import com.school.panorama.mapper.TeacherMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 基础对象的只读访问层，每次查询都从库取，保证各视角数据一致。 */
@Service
public class CatalogService {

    @Autowired private SchoolMapper schoolMapper;
    @Autowired private TeacherMapper teacherMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private ClassroomMapper classroomMapper;
    @Autowired private ClazzMapper clazzMapper;
    @Autowired private StudentMapper studentMapper;

    public List<School> schools() { return schoolMapper.selectList(null); }
    public List<Teacher> teachers() { return teacherMapper.selectList(null); }
    public List<Course> courses() { return courseMapper.selectList(null); }
    public List<Classroom> classrooms() { return classroomMapper.selectList(null); }
    public List<Clazz> classes() { return clazzMapper.selectList(null); }
    public List<Student> students() { return studentMapper.selectList(null); }

    public School school(Long id) {
        return id == null ? null : schools().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public Teacher teacher(Long id) {
        return id == null ? null : teachers().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public Course course(Long id) {
        return id == null ? null : courses().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public Classroom classroom(Long id) {
        return id == null ? null : classrooms().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public Clazz clazz(Long id) {
        return id == null ? null : classes().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public Student student(Long id) {
        return id == null ? null : students().stream().filter(x -> Objects.equals(x.id, id)).findFirst().orElse(null);
    }

    public String schoolName(Long id) {
        School s = school(id);
        return s == null ? null : s.name;
    }

    public List<Student> studentsOfClass(long classId) {
        return students().stream().filter(s -> s.classId == classId).toList();
    }

    public List<Long> studentIdsOfClass(long classId) {
        return studentsOfClass(classId).stream().map(s -> s.id).toList();
    }

    public List<Clazz> classesOfSchool(long schoolId) {
        return classes().stream().filter(c -> c.schoolId == schoolId).toList();
    }

    public List<Teacher> teachersOfSchool(long schoolId) {
        return teachers().stream().filter(t -> t.schoolId == schoolId).toList();
    }

    public List<Classroom> classroomsOfSchool(long schoolId) {
        return classrooms().stream().filter(c -> c.schoolId == schoolId).toList();
    }

    public long countStudentsOfSchool(long schoolId) {
        Set<Long> classIds = classesOfSchool(schoolId).stream().map(c -> c.id).collect(Collectors.toSet());
        return students().stream().filter(s -> classIds.contains(s.classId)).count();
    }

    public long countStudentsOfClass(long classId) {
        return studentsOfClass(classId).size();
    }
}
