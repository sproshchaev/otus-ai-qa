package ru.otus.aiqa.userservice.domain;

public class User {

    private Long id;
    private String name;
    private String email;
    private Integer age;
    private boolean active;

    public User() {
    }

    public User(Long id, String name, String email, Integer age, boolean active) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.age = age;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
