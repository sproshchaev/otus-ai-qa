package ru.otus.aiqa.userservice.repository;

import ru.otus.aiqa.userservice.domain.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/** Хранилище в памяти: для учебного сервиса база не нужна, а запуск становится мгновенным. */
@Repository
public class UserRepository {

    private final ConcurrentSkipListMap<Long, User> storage = new ConcurrentSkipListMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public User save(User user) {
        if (user.getId() == null) {
            user.setId(sequence.incrementAndGet());
        }
        storage.put(user.getId(), user);
        return user;
    }

    public Optional<User> findById(long id) {
        return Optional.ofNullable(storage.get(id));
    }

    public boolean deleteById(long id) {
        return storage.remove(id) != null;
    }

    public List<User> findAll() {
        return new ArrayList<>(storage.values());
    }

    public int count() {
        return storage.size();
    }
}
