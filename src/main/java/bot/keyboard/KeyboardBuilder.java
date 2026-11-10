package bot.keyboard;

import bot.model.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class KeyboardBuilder {
    // === Статические фабрики для типовых клавиатур ===

    public static Keyboard settingsKeyboard(boolean show) {
        Button showBtn = new Button("Отображать");
        showBtn.setCallback("settings_show");
        if (show)
            showBtn.setColor(Button.ColorOfButton.GREEN);

        Button hideBtn = new Button("Не отображать");
        hideBtn.setCallback("settings_hide");
        if (!show)
            hideBtn.setColor(Button.ColorOfButton.GREEN);

        return new Keyboard(Keyboard.KeyboardType.INLINE, new Button[][]{
                new Button[]{
                        showBtn,
                        hideBtn
                }
        }
        );
    }
    public static Keyboard commitKeyboard() {
        Button commitBtn = new Button("Сохранить");
        commitBtn.setCallback("commit");
        commitBtn.setColor(Button.ColorOfButton.GREEN);

        Button abortBtn = new Button("Отменить сохранение");
        abortBtn.setCallback("cancel");
        abortBtn.setColor(Button.ColorOfButton.RED);

        return new Keyboard(Keyboard.KeyboardType.INLINE, new Button[][]{
                new Button[]{
                        commitBtn,
                        abortBtn
                }
        }
        );
    }

    // === Статический build для документных workflow ===

    public static Keyboard build(Paper paper) {
        DocType docType = paper.getDocType();
        Stage stage = paper.getStage();
        int docId = paper.getDocId();

        return switch (stage) {
            case SUBMITTED -> {
                switch (docType) {
                    case INVOICE -> {
                        Button startAccepting = new Button("Начать согласование", Stage.ACCEPTING, docId, Button.ColorOfButton.BLUE);
                        Button block = new Button("Отказать", Stage.BLOCKED_AT_ACCEPTANCE, docId, Button.ColorOfButton.RED);
                        yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                                {startAccepting},
                                {block}
                        });
                    }
                    case ACT -> {
                        Button startPaying = new Button("Начать приемку", Stage.PAYING, docId, Button.ColorOfButton.BLUE);
                        Button block = new Button("Отказать", Stage.BLOCKED_AT_PAYMENT, docId, Button.ColorOfButton.RED);
                        yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                                {startPaying},
                                {block}
                        });
                    }
                    default -> {
                        yield null;
                    }
                }
            }
            case ACCEPTING -> {
                Button acceptNoReg = new Button("Согласовать без регистрации", Stage.ACCEPTED_NO_REG, docId, Button.ColorOfButton.GREEN);
                Button acceptWithReg = new Button("Согласовать с регистрацией", Stage.ACCEPTED_WITH_REG, docId, Button.ColorOfButton.GREEN);
                Button block = new Button("Отказать", Stage.BLOCKED_AT_ACCEPTANCE, docId, Button.ColorOfButton.RED);
                Button returnBack = new Button("Вернуть коллегам", Stage.SUBMITTED, docId, Button.ColorOfButton.BLUE);
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {acceptNoReg},
                        {acceptWithReg},
                        {block},
                        {returnBack}
                });
            }
            case ACCEPTED_NO_REG -> {
                Button startPaying = new Button("Начать оплату", Stage.PAYING, docId, Button.ColorOfButton.BLUE);
                Button block = new Button("Отказать", Stage.BLOCKED_AT_PAYMENT, docId, Button.ColorOfButton.RED);
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {startPaying},
                        {block}
                });
            }
            case ACCEPTED_WITH_REG -> {
                Button startRegistering = new Button("Начать регистрацию", Stage.REGISTERING, docId, Button.ColorOfButton.BLUE);
                Button block = new Button("Отказать", Stage.BLOCKED_AT_REGISTRATION, docId, Button.ColorOfButton.RED);
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {startRegistering},
                        {block}
                });
            }
            case REGISTERING -> {
                Button register = new Button("Зарегистрировать", Stage.REGISTERED, docId, Button.ColorOfButton.GREEN);
                Button block = new Button("Отказать", Stage.BLOCKED_AT_REGISTRATION, docId, Button.ColorOfButton.RED);
                Button returnBack = new Button("Вернуть коллегам", Stage.ACCEPTED_WITH_REG, docId, Button.ColorOfButton.BLUE);
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {register},
                        {block},
                        {returnBack}
                });
            }
            case REGISTERED -> {
                Button startPaying = new Button("Начать оплату", Stage.PAYING, docId, Button.ColorOfButton.BLUE);
                Button block = new Button("Отказать", Stage.BLOCKED_AT_PAYMENT, docId, Button.ColorOfButton.RED);
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {startPaying},
                        {block}
                });
            }
            case PAYING -> {
                Button pay = null;
                if (docType == DocType.INVOICE) {
                    pay = new Button("Оплатить", Stage.PAID, docId, Button.ColorOfButton.GREEN);
                } else if (docType == DocType.ACT) {
                    pay = new Button("Принять", Stage.PAID, docId, Button.ColorOfButton.GREEN);
                }
                Button block = new Button("Отказать", Stage.BLOCKED_AT_PAYMENT, docId, Button.ColorOfButton.RED);
                Button returnBack;
                if (docType == DocType.INVOICE && paper.getNeedsRegistration()) {
                    returnBack = new Button("Вернуть коллегам", Stage.REGISTERED, docId, Button.ColorOfButton.BLUE);
                } else if (docType == DocType.INVOICE) {
                    returnBack = new Button("Вернуть коллегам", Stage.ACCEPTED_NO_REG, docId, Button.ColorOfButton.BLUE);
                } else {
                    returnBack = new Button("Вернуть коллегам", Stage.SUBMITTED, docId, Button.ColorOfButton.BLUE);
                }
                yield new Keyboard(Keyboard.KeyboardType.INLINE,
                                new Button[][]{
                        {pay},
                        {block},
                        {returnBack}
                });
            }
            default -> null;
        };
    }
    public static Keyboard viewDocsMenuKeyboard(User user) {

        if (user.hasRole(Role.ADMIN)) {
            if (user.hasRole(Role.USER)) {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Просмотреть последние 30 переданных документов") },
                        new Button[] { new Button("Просмотреть последние 15 переданных вами документов") },
                        new Button[] { new Button("Просмотреть полную информацию по конкретному документу") },
                        new Button[] { new Button("Вернуться в главное меню") }
                }
                );
            } else {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Просмотреть последние 30 переданных документов") },
                        new Button[] { new Button("Просмотреть полную информацию по конкретному документу") },
                        new Button[] { new Button("Вернуться в главное меню") }
                }
                );
            }
        }
        else if (user.hasRole(Role.USER)) {
            return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                    new Button[] { new Button("Просмотреть последние 15 переданных вами документов") },
                    new Button[] { new Button("Просмотреть полную информацию по конкретному документу") },
                    new Button[] { new Button("Вернуться в главное меню") }
            }
            );
        }
        else {
            return null;
        }
    }

    public static Keyboard stopTransferKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                new Button[] { new Button("Перестать передавать документы") }
        }
        );
    }



    // === Reply keyboards ===

    public static Keyboard mainMenuKeyboard(User user) {
        boolean hasOtherRoles = user.hasRole(Role.APPROVER) || user.hasRole(Role.REGISTRAR) || user.hasRole(Role.RECEIVER);
        if (user.hasRole(Role.ADMIN)) {
            if (hasOtherRoles) {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Передать документы") },
                        new Button[] { new Button("Просмотреть документы, ожидающие меня") },
                        new Button[] { new Button("Изменить cписoк пользователей бота") },
                        new Button[] { new Button("Управление проектами") },
                        new Button[] { new Button("Просмотреть информацию о документах") },
                        new Button[] { new Button("Настройки") }
                }
                );
            } else {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Передать документы") },
                        new Button[] { new Button("Изменить cписoк пользователей бота") },
                        new Button[] { new Button("Управление проектами") },
                        new Button[] { new Button("Просмотреть информацию о документах") },
                        new Button[] { new Button("Настройки") }
                }
                );
            }
        }
        if (user.hasRole(Role.USER)) {
            if (hasOtherRoles) {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Передать документы") },
                        new Button[] { new Button("Просмотреть документы, ожидающие меня") },
                        new Button[] { new Button("Просмотреть информацию о переданных документах") },
                        new Button[] { new Button("Настройки") }
                }
                );
            } else {
                return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                        new Button[] { new Button("Передать документы") },
                        new Button[] { new Button("Просмотреть информацию о переданных документах") },
                        new Button[] { new Button("Настройки") }
                }
                );
            }
        }
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                new Button[] { new Button("Просмотреть документы, ожидающие меня") }
        }
        );
    }

    public static Keyboard docTypeKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][] {
                new Button[] { new Button("Акт"), new Button("Счет на оплату") },
                new Button[] { new Button("Прочее") },
        }
        );
    }

    public static Keyboard changeRoleMenuKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[]{new Button("Изменить список пользователей")},
                new Button[]{new Button("Просмотреть список псевдонимов пользователей")},
                new Button[]{new Button("Изменить список администраторов")},
                new Button[]{new Button("Просмотреть список псевдонимов администраторов")},
                new Button[]{new Button("Назад")}
        }
        );
    }

    public static Keyboard changeUserListKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[] { new Button("Добавить пользователя"), new Button("Удалить пользователя") },
                new Button[] { new Button("Изменить имя пользователя") },
                new Button[] { new Button("Просмотреть список псевдонимов пользователей") },
                new Button[] { new Button("Назад") }
        }
        );
    }

    public static Keyboard changeAdminListKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[] { new Button("Добавить администратора"), new Button("Удалить администратора") },
                new Button[] { new Button("Изменить имя администратора") },
                new Button[] { new Button("Просмотреть список псевдонимов администраторов") },
                new Button[] { new Button("Назад") }
        }
        );
    }

    public static Keyboard docDetailsWithBackKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[]{new Button("Просмотреть полную информацию по конкретному документу")},
                new Button[]{new Button("Вернуться в главное меню")}
        }
        );
    }

    public static Keyboard projectDetailsWithBackKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[]{new Button("Просмотреть полную информацию по конкретному проекту")},
                new Button[]{new Button("Вернуться в главное меню")}
        }
        );
    }

    public static Keyboard extraInformationKeyboard() {
        return new Keyboard(Keyboard.KeyboardType.REPLY, new Button[][]{
                new Button[]{new Button("Дополнительная информация не требуется")}
        }
        );
    }

    public static Keyboard enterInlineMode() { return new Keyboard(false); }
    public static Keyboard removeKeyboard() {
        return new Keyboard(true);
    }
}
