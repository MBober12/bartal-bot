package bot.model;

import java.util.HashSet;
import java.util.Set;

public class User {
    private final long chatId;
    private String name;
    private String state;
    private String previousMessage;
    private String mesIdsAndDocIds;
    private boolean showInfo;
    private Set<Role> roles = new HashSet<>();

    public User(long chatId, String name, Role role) {
        this.chatId = chatId;
        this.name = name;
        roles.add(role);
    }

    public User(long chatId, String name) {
        this.chatId = chatId;
        this.name = name;
    }

    public long getChatId() {
        return chatId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setState(UserState state) {
        this.state = state != null ? state.getText() : "";
    }

    public String getPreviousMessage() {
        return previousMessage;
    }

    public void setPreviousMessage(String previousMessage) {
        this.previousMessage = previousMessage;
    }

    public String getMesIdsAndDocIds() {
        return mesIdsAndDocIds;
    }

    public void setMesIdsAndDocIds(String mesIdsAndDocIds) {
        this.mesIdsAndDocIds = mesIdsAndDocIds;
    }

    public boolean getShowInfo() {
        return showInfo;
    }

    public void setShowInfo(boolean showInfo) {
        this.showInfo = showInfo;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void addRole(Role role) {
        this.roles.add(role);
    }

    public void removeRole(Role role) {
        this.roles.remove(role);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
