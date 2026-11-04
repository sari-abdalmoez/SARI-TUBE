# SARI TUBE 0.4.0 — ما تغيّر وما لم يُختبر

## المضاف
- مكتبة youtubedl-android (yt-dlp مع Python وFFmpeg) في build.gradle.kts، مع JitPack في settings.gradle.kts.
- تقسيم APK حسب المعمارية (arm64-v8a و armeabi-v7a)، مع useLegacyPackaging لاستخراج Python.
- SariTubeApp: يهيّئ yt-dlp في خيط خلفي عند بدء التطبيق.
- YtDlpEngine: التهيئة وانتظار الجاهزية، و YtDlpUpdater: تحديث تلقائي كل 24 ساعة وعند فشل البحث بسبب تغيّر يوتيوب.
- YtDlpSearch: ytsearch20:<query> مع --flat-playlist و--dump-json، ومهلة 45 ثانية يوقف بعدها العملية، وتحليل JSON سطراً سطراً مع إزالة التكرار.
- YtDlpDownloader: تنزيل يوتيوب بجودات (أفضل، 1080، 720، 480، صوت)، مع إيقاف عند الإلغاء واختيار الملف النهائي.
- DownloadService: فرع يوتيوب يستخدم yt-dlp، وبقية الروابط تستخدم المحمّل المباشر كما كان.
- SearchViewModel: بحث في Dispatchers.IO، تأخير 700 ms للكتابة، إلغاء البحث السابق عند استعلام جديد، ذاكرة لعشرين استعلاماً.
- SearchScreen: شريط بحث، ScrollableTabRow بالتبويبات الست، بطاقات فيديو مع Coil، زر تنزيل بجانب كل فيديو.
- MainActivity: زر "بحث عن فيديو أو أغنية" في الشاشة الرئيسية.

## لم يُختبر هنا (بيئة البناء بلا Gradle ولا شبكة)
- البناء نفسه وأي خطأ تجميع محتمل في أسماء واجهات youtubedl-android (execute مع processId، destroyProcessById، DownloadProgressCallback، updateYoutubeDL، FFmpeg).
- رقم الإصدار 0.17.5 غير مؤكد. يجب التحقق من آخر إصدار في صفحة Releases قبل البناء.
- البحث الحقيقي والتنزيل من يوتيوب والإيقاف والاستئناف.
- تصنيف "أغاني" و"فيديو" تقريبي بالكلمات المفتاحية.
- التبويبات Google وTikTok وFacebook تعرض رسالة توضيحية ولا تبحث.
- التحميل من يوتيوب مخالف لشروطه، والمسؤولية على المستخدم.
