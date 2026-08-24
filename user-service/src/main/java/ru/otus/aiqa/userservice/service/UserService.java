package ru.otus.aiqa.userservice.service;

import ru.otus.aiqa.userservice.domain.User;
import ru.otus.aiqa.userservice.dto.PageResponse;
import ru.otus.aiqa.userservice.dto.UserRequest;
import ru.otus.aiqa.userservice.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User create(UserRequest request) {
        return repository.save(new User(null, request.name(), request.email(), request.age(), false));
    }

    public Optional<User> findById(long id) {
        return repository.findById(id);
    }

    public Optional<User> update(long id, UserRequest request) {
        return repository.findById(id).map(user -> {
            user.setName(request.name());
            user.setEmail(request.email());
            user.setAge(request.age());
            return repository.save(user);
        });
    }

    public boolean delete(long id) {
        return repository.deleteById(id);
    }

    public Optional<User> activate(long id) {
        return repository.findById(id).map(user -> {
            user.setActive(true);
            return repository.save(user);
        });
    }

    public PageResponse<User> findPage(int page, int size) {
        List<User> all = repository.findAll();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponse<>(all.subList(from, to), page, size, all.size());
    }
}
