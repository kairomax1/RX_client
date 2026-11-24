# RX client v1.2

بعد از یه مدت طولانی، **RX client** برگشت — این بار با نسخهٔ **1.2** و یک مشت قابلیت تازه. یکی‌یکی می‌رویم سراغشان:

## ۱. 🦊 موزیلا — وصل شدن از داخل خود برنامه

موزیلا به برنامه اضافه شده: مستقیم داخل برنامه، به‌صورت وب ثبت‌نام می‌کنید و وصل می‌شوید. پشت این متد، تمام پروتکل‌ها و MTU، fragment و کلی موارد دیگه به‌صورت خودکار روی اینترنت شما اعمال می‌شود تا بهترین سرعت را بگیرید و لذت ببرید.

## ۲. 🎮 حالت گیمینگ

خبر خوش برای گیمرهای عزیز! از این به بعد می‌توانید با متدها و کانفیگ‌های داخل برنامه برای گیم استفاده کنید. حواستان باشد همه‌چیز به کانفیگ شما بستگی دارد؛ پس اگر کند بود، پینگ بالا داشت یا هر مشکل دیگری داشت، از کانفیگ شماست. هر مشکلی هم بود، بروید تو کانال تلگرامی مطرح کنید تا در نسخه‌های بعدی حل و عملی شود.

## ۳. 🌐 اسکنر DNS

این متد برای کسانی است که مشکل ورود یا لاگین در کنسول ایکس‌باکس دارند. واقعاً آدم دردش می‌آید که برود تو اینترنت کلی بگردد و هیچ DNS‌ای هم جواب ندهد. حدود **۱۰۰ هزار** DNS داخل برنامه هست که می‌توانید استفاده کنید.

## ۴. ⚙️ انتخاب خودکار fragment و mux

در بخش تنظیمات، یک بخش برای fragment و mux اضافه شده به اسم **«اعمال بهترین حالت»**. کمک می‌کند در شرایط سخت اینترنت، بهترین اعمال روی fragment و mux را برای اینترنت خودتان داشته باشید.

## ۵. 🆓 کانفیگ رایگان عمومی

در برنامه می‌توانید از کانفیگ رایگان عمومی استفاده کنید. با توجه به شرایط اینترنت کشور، کانفیگ‌ها به‌صورت منطقه‌ای کار می‌کنند؛ ممکن است برای یک نفر خوب باشد و برای یک نفر بد یا ضعیف — بستگی به منطقهٔ شما دارد. در نسخه‌های بعدی سعی می‌کنیم این مشکل را حل کنیم.

---

امیدواریم **RX client** برایتان مفید باشد. برای حمایت می‌توانید به پروژه **استار** بدهید یا در کانال تلگرام و یوتیوب عضو شوید تا از خبرها جا نمانید.

مخلص شما، تیم **Aimobix**

---

# RX client v1.2 — English

After a long break, **RX client** is back with **v1.2** and a fresh set of features. Here they are, one by one:

## 1. 🦊 Mozilla — connect from inside the app

Mozilla has been added to the app: you register and connect through a web flow without leaving it. Behind this method, all protocols, MTU, fragmentation and a bunch of other settings are applied automatically to your connection, so you get the best possible speed.

## 2. 🎮 Gaming mode

Good news for gamers: you can now use the app's methods and configs for gaming. Keep in mind that everything depends on your config — if it's slow, high-ping or misbehaving in any way, it's your config. If you hit a problem, join the Telegram channel and report it so we can address it in upcoming releases.

## 3. 🌐 DNS scanner

This one is for people who can't sign in or log in on an Xbox console. It genuinely hurts to crawl the internet looking for a DNS that actually answers. The app ships with roughly **100,000** DNS servers you can use.

## 4. ⚙️ Auto-select fragment and mux

Settings now has a fragment/mux section called **"Apply best mode"**. It helps you get the best fragment and mux settings for your own connection when the network is difficult.

## 5. 🆓 Free public configs

You can use free public configs inside the app. Because of the country's network conditions, these configs work regionally: one may be fine for one person and weak for another, depending on your region. We're working on improving this in future releases.

---

We hope **RX client** is useful for you. To support the project, give it a **star**, or join the Telegram channel and YouTube so you don't miss the news.

— The **Aimobix** team

---

## 📥 دانلود و نصب / Download & install

| فایل / File | برای / For |
| --- | --- |
| `RXVPN_1.2_arm64-v8a.apk` | اکثر گوشی‌های اندرویدی (۶۴ بیت) — **پیشنهادی** / most Android phones (64-bit) — **recommended** |
| `RXVPN_1.2_armeabi-v7a.apk` | گوشی‌های قدیمی ۳۲ بیتی / older 32-bit devices |
| `RXVPN_1.2_x86_64.apk` | امولاتورها / emulators |
| `RXVPN_1.2_x86.apk` | دستگاه‌های x86 قدیمی / legacy x86 devices |
| `RXVPN_1.2_universal.apk` | همهٔ پردازنده‌ها (حجم بیشتر) / all ABIs (larger download) |

- اندروید ۷ به بالا / Android 7.0+ (API 24)
- روی نسخهٔ قبلی نصب می‌شود، بدون نیاز به حذف / installs over the previous version — no uninstall needed
- اگر از بیلد F-Droid می‌آیید، اول حذف کنید (امضای متفاوت) / coming from an F-Droid build requires an uninstall (different signature)

## 🔐 راستی‌آزمایی / Verify

```sh
sha256sum RXVPN_1.2_arm64-v8a.apk   # سپس با مقدار زیر مقایسه کنید / compare with the value below
```

```
a90870e6337d4362e701a79e24ed141c41816d6d3abf0c041c849fae0d694341  RXVPN_1.2_arm64-v8a.apk
37388a49dd16884cfee46c5f0464869684eff331a8df6b4a79fae88cbced3b23  RXVPN_1.2_armeabi-v7a.apk
be869efdfaf892ec2faa7ddeb9fbc67e27a2209da86277c3fab56e70561d12ae  RXVPN_1.2_universal.apk
cf8e3057e424708d8e6e854d65186f8cea7d4ec8ec10f630c5c8205b51ae76fb  RXVPN_1.2_x86.apk
b825f8809b497b1c75b26c02b16cb73ab62db6ff5c7d024c08ddcdc5a9916cfa  RXVPN_1.2_x86_64.apk
```

اثر انگشت سرتیفیکیت امضاکننده / signing certificate SHA-256:

```
52:86:4C:67:AF:63:07:4A:B2:A6:13:64:3A:4B:09:43:4E:3D:32:0B:87:DC:9C:1D:6B:EF:51:4D:88:1A:5C:83
```

## 🔗 لینک‌ها / Links

- کد منبع / Source: https://github.com/kairomax1/RX_client
- لایسنس / License: **GPL-3.0**
- سیاست حریم خصوصی / Privacy policy: `CR.md`
- تلگرام / Telegram: https://t.me/aimobix
- یوتیوب / YouTube: https://youtube.com/@aimobixnet

بیلدشده از کامیت / Built from commit `b0447107f4835e5e650987b5f653d160d92049ba`

---

## دربارهٔ RX client / About

**RX client** یک کلاینت اندرویدی رایگان و متن‌باز برای هسته‌های V2Ray/Xray است، بر پایهٔ
[v2rayNG](https://github.com/2dust/v2rayNG). پروتکل‌های VLESS، VMess، Trojan، Shadowsocks،
WireGuard، Hysteria2، TUIC، SOCKS و HTTP را پشتیبانی می‌کند و هیچ تبلیغ، آنالیتیکس یا SDK
گزارش خطا ندارد. کانفیگ سرور را خودتان اضافه می‌کنید؛ برنامه سرویس VPN نمی‌فروشد.

**RX client** is a free, open-source Android client for the V2Ray/Xray cores, based on
[v2rayNG](https://github.com/2dust/v2rayNG). It supports VLESS, VMess, Trojan, Shadowsocks,
WireGuard, Hysteria2, TUIC, SOCKS and HTTP, ships no ads, no analytics and no crash-reporting
SDK, and does not sell VPN service — you bring your own server configuration.
