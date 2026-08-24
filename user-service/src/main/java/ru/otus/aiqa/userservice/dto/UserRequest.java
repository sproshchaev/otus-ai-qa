package ru.otus.aiqa.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Тело запроса на создание и обновление пользователя. Границы намеренно круглые: их удобно проверять. */
public record UserRequest(

        @NotNull
        @Size(min = 2, max = 50, message = "имя должно быть длиной от 2 до 50 символов")
        String name,

        @NotNull
        @Email(message = "email должен быть корректным адресом")
        String email,

        @NotNull
        @Min(value = 18, message = "возраст не меньше 18")
        @Max(value = 120, message = "возраст не больше 120")
        Integer age) {
}
