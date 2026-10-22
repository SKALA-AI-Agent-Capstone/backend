package org.skala.capstone.auth.dto;

public record TeamMemberRequest(String email, String password, String name, String role) {}
