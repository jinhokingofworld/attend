package com.example.attend.helper;

import com.example.attend.entity.Member;
import com.example.attend.form.MemberForm;

public class MemberHelper {
    public static Member convertMember(MemberForm form) {
        Member member = new Member();
        member.setId(form.getId());
        member.setName(form.getName());
        member.setPhone(form.getPhone());
        member.setAge(form.getAge());
        member.setBirth(form.getBirth());
        member.setCardUid(form.getCardUid());
        return member;
    }

    public static MemberForm convertMemberForm(Member member) {
        MemberForm form = new MemberForm();
        form.setId(member.getId());
        form.setName(member.getName());
        form.setAge(member.getAge());
        form.setPhone(member.getPhone());
        form.setBirth(member.getBirth());
        form.setCardUid(member.getCardUid());
        return form;
    }
}
