package bot.model;

public enum Stage {
    SUBMITTED,              // пользователь передал документ
    ACCEPTING,              // утверждающий начал работу
    ACCEPTED_NO_REG,        // согласован без регистрации
    ACCEPTED_WITH_REG,      // согласован с регистрацией
    REGISTERING,            // регистратор начал работу
    REGISTERED,             // зарегистрирован
    PAYING,                 // получатель начал работу
    PAID,                   // оплачен/принят
    BLOCKED_AT_ACCEPTANCE,  // заблокирован утверждающим
    BLOCKED_AT_REGISTRATION,// заблокирован регистратором
    BLOCKED_AT_PAYMENT      // заблокирован получателем
}
