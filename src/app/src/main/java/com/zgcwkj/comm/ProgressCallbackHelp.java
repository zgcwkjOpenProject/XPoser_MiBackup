package com.zgcwkj.comm;

/**
 * 小米备份进度回调参数工具
 * 原生NASTransferService会直接对回调里的String调用isEmpty，传null会导致备份进程崩溃
 */
public final class ProgressCallbackHelp {

    private ProgressCallbackHelp() {
    }

    /**
     * 将可能为空的字符串转换为非null字符串
     *
     * @param value 原始字符串
     * @return value为null时返回空字符串，否则返回原值
     */
    public static String safeString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 清洗反射调用进度回调时的String参数
     * 只处理参数类型声明为String且实参为null的位置，其他参数保持不变
     *
     * @param types 目标方法参数类型
     * @param args 目标方法实参
     * @return 清洗后的实参数组
     */
    public static Object[] sanitizeStringArgs(Class<?>[] types, Object[] args) {
        if (types == null || args == null) {
            return args;
        }
        for (var i = 0; i < types.length && i < args.length; i++) {
            if (types[i] == String.class && args[i] == null) {
                args[i] = "";
            }
        }
        return args;
    }
}
