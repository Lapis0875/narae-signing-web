package com.naraesigning.admin;

@FunctionalInterface
interface AdminConsole {
    char[] readPassword(String prompt);
}
