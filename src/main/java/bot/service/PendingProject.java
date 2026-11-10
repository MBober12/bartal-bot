package bot.service;
import bot.model.Role;

import java.util.*;

public class PendingProject {
    public final String name;
    public final String description;
    Map<String, Long> nameToChatId;
    public final Map<Long, MatchedUser> users = new TreeMap<>();

    public PendingProject(String name, String description, Map<String, Long> nameToChatId) {
        this.name = name;
        this.description = description;
        this.nameToChatId = nameToChatId;
    }

    public void setUsers(List<String> validUsers, Role role) {
        for (String validUser : validUsers) {
            addRole(validUser, role);
        }
    }
    public static class MatchedUser {
        public boolean isUser;
        public boolean isApprover;
        public boolean isRegistrar;
        public boolean isReceiver;

        public MatchedUser(Role role) {
            switch (role) {
                case USER -> isUser = true;
                case APPROVER -> isApprover = true;
                case REGISTRAR -> isRegistrar = true;
                case RECEIVER -> isReceiver = true;
            }
        }
    }
    public void addRole(String name, Role role) {
        long chatId = nameToChatId.get(name);
        if (users.containsKey(chatId)) {
            switch (role) {
                case USER -> {
                    users.get(chatId).isUser = true;
                }
                case APPROVER -> {
                    users.get(chatId).isApprover = true;
                }
                case REGISTRAR -> {
                    users.get(chatId).isRegistrar = true;
                }
                case RECEIVER -> {
                    users.get(chatId).isReceiver = true;
                }
            }
        } else {
            users.put(chatId, new MatchedUser(role));
        }
    }
}