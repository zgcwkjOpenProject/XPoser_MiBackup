# Xposed 相关
-keep class com.xp.jdauto.hook.JDHookEntry { *; }
-keep class com.xp.jdauto.service.JDAccessibilityService { *; }

# 保留所有 task 类
-keep class com.xp.jdauto.task.** { *; }
