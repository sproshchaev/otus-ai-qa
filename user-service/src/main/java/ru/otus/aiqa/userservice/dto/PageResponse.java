package ru.otus.aiqa.userservice.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, int total) {
}
