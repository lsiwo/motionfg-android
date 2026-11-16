# Motion FG for Android 1.0.0-beta

First public beta of Motion FG. It adds GPU frame generation and upscaling to Android games and exports processed videos to MP4.

- **Games.** ×2, ×3, and ×4 frame generation, four scaling profiles, a low-latency mode, optional V-Sync, and live controls through the floating **FG** button.
- **Full-frame output.** Processing covers the entire captured image. All four edges remain visible when the capture and display use different aspect ratios.
- **Frame pacing and quality.** A bounded GPU queue drops expired phases during overload. Generation guards high-contrast HUD and motion boundaries against ghosting. The upscaler validates frame history before reusing it.
- **Video and color.** MP4 export with frame interpolation, scaling, optional audio copy, and HDR10 on compatible hardware. The game overlay reports its active SDR or HDR output format.

Requires Android 14+ and OpenGL ES 3.0. The interface is in Russian. In Android's capture prompt, select **A single app** for game mode. HDR and export limits vary by device and driver. The FPS overlay reports accepted game captures and submitted output frames.

Feedback is welcome in GitHub Issues. Please include your device, Android build, game or video, settings, and steps to reproduce.

---

## Русский

Первая публичная бета Motion FG. Приложение добавляет генерацию кадров и апскейлинг для игр на Android и сохраняет обработанные видео в MP4.

- **Игры.** Генерация ×2, ×3 и ×4, четыре профиля апскейлинга, малая задержка, переключатель V-Sync и быстрые настройки через плавающую кнопку **FG**.
- **Вывод до краёв.** Обработка охватывает весь захваченный кадр. При разных пропорциях все четыре края остаются видимыми.
- **Плавность и качество.** При перегрузке GPU пропускаются запоздавшие фазы генерации. Шейдеры уменьшают двоение интерфейса и контуров движущихся объектов. Апскейлинг проверяет историю перед её повторным использованием.
- **Видео и цвет.** Экспорт MP4 с генерацией, масштабированием, копированием звука и HDR10 на совместимом оборудовании. Панель игры показывает текущий формат SDR или HDR.

Требуются Android 14+ и OpenGL ES 3.0. Для игры выбери **Одно приложение** в диалоге захвата Android. HDR и доступные параметры экспорта зависят от телефона и драйвера. Счётчик FPS показывает принятые кадры игры и кадры, отправленные на вывод.

Жду обратную связь в GitHub Issues. Укажи устройство, прошивку, игру или видео, настройки и шаги воспроизведения.
