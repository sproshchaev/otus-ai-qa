package ru.otus.aiqa.userservice.controller;

import ru.otus.aiqa.userservice.domain.User;
import ru.otus.aiqa.userservice.dto.PageResponse;
import ru.otus.aiqa.userservice.dto.UserRequest;
import ru.otus.aiqa.userservice.service.UserService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<User> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{id}")
    public User findById(@PathVariable long id) {
        return service.findById(id).orElseThrow(() -> notFound(id));
    }

    @GetMapping
    public PageResponse<User> findPage(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.findPage(page, size);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable long id, @Valid @RequestBody UserRequest request) {
        return service.update(id, request).orElseThrow(() -> notFound(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!service.delete(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public User activate(@PathVariable long id) {
        return service.activate(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "пользователь " + id + " не найден");
    }
}
