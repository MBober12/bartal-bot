package bot.model;

public enum UserState {

    EMPTY_STATE(""),
    // Управление пользователями — ввод ID
    ADDING_USER("Добавить пользователя"),
    REMOVE_USER("Удалить пользователя"),
    ADDING_ADMIN("Добавить администратора"),
    REMOVING_ADMIN("Удалить администратора"),

    // Управление пользователями — ввод имени
    ADDING_USER_ALIAS("Добавить псевдоним пользователя"),
    ADDING_ADMIN_ALIAS("Добавить псевдоним администратора"),

    // Смена имени пользователя
    CHANGING_USER_ID("Изменить имя пользователя (ввод ID)"),
    CHANGING_USER_NAME("Изменить имя пользователя (ввод имени)"),

    //Управление проектами - ввод таблицы с проектами
    SENDING_PROJECTS_TABLE("Отправьте таблицу проектов"),
    COMMITING("ПодтвердитеСохранение"),

    //Просмотр проекта
    ENTERING_PROJECT_NAME("Введите имя проекта"),

    // Просмотр документа
    ENTERIND_DOC_ID("Введите DocId документа"),

    // Передача документа — цепочка
    ENTERING_DOCUMENT_TYPE("Тип документа."),
    ENTERING_DOC_PROJECT_NAME("К какому объекту относится передаваемый документ."),
    ENTERING_EXTRA_INFO("Укажите дополнительную информацию"),
    ENTERING_RESPONSIBLE_PERSON("Укажите ответственного."),
    ENTERING_RECEIPT("Укажите сумму платежа."),
    ENTERING_CONTRACTOR("Укажите контрагента-получателя."),
    PASSING_DOC("Передача документа"),

    // Блокировка — ожидание причины
    ENTERING_APPROVING_BLOCK_REASON("Введите причину блокировки"),
    ENTERING_REGISTRATION_BLOCK_REASON("Введите причину отказа в регистрации"),
    ENTERING_PAYMENT_BLOCK_REASON("Введите причину отказа в оплате");

    private final String text;

    UserState(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static UserState fromText(String text) {
        for (UserState state : values()) {
            if (state.name().equalsIgnoreCase(text) || state.getText().equalsIgnoreCase(text)) {
                return state;
            }
        }
        return null;
    }
}
