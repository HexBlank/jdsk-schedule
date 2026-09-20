# WebView 与 JS 的桥接方法必须保留，否则 R8 会重命名导致注入脚本调不到
-keepclassmembers class com.zhusijiao.app.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# OkHttp / Kotlin 协程自带的 consumer 规则已足够，这里仅补充平台告警抑制
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
