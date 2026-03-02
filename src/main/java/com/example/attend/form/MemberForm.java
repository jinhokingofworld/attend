package com.example.attend.form;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberForm {
    private Long id;
    @NotBlank(message = "이름은 필수입니다.")
    private String name;

    @NotNull(message = "나이를 입력하세요.")
    @Min(value = 0, message = "나이는 음수가 될 수 없습니다.")
    //Integer를 사용해야 함 wrapper라 null 가능 → @NotNull 작동, primitive는 null 불가능 → @NotNull 의미 없음
    @Max(value = 120, message = "나이가 120세를 초과할 수 없습니다.")
    private Integer age;
    @Pattern(regexp = "^\\d{2,3}-\\d{3,4}-\\d{4}$", message = "휴대폰 번호 양식에 맞지 않습니다.")
    private String phone;
    private LocalDate birth;
    private String cardUid;
}
