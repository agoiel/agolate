"""Главная панель управления: кнопки, горячие клавиши, режим «Авто»."""

from __future__ import annotations

import sys
import traceback
from pathlib import Path

import numpy as np
from PIL import Image
from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, QTimer, Signal
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (QApplication, QCheckBox, QFileDialog, QGridLayout, QLabel,
                               QMessageBox, QPushButton, QVBoxLayout, QWidget)

from .. import __version__, languages
from ..capture import Region, grab_region
from ..config import ROOT, Config
from ..models import PageResult
from ..pipeline import Pipeline
from ..translators import TRANSLATORS
from .list_window import ListWindow
from .overlay import OverlayWindow
from .selector import SelectionWindow
from .settings_dialog import SettingsDialog


# ================================================================ фоновая работа
class _Signals(QObject):
    done = Signal(object, float, object)    # PageResult, k, токен задачи
    failed = Signal(str)


class _Job(QRunnable):
    def __init__(self, pipeline: Pipeline, image: Image.Image, k: float, token):
        super().__init__()
        self.pipeline, self.image, self.k, self.token = pipeline, image, k, token
        self.signals = _Signals()

    def run(self):
        try:
            res = self.pipeline.process(self.image)
            self.signals.done.emit(res, self.k, self.token)
        except Exception:  # noqa: BLE001
            self.signals.failed.emit(traceback.format_exc())


class _HotkeyBridge(QObject):
    fired = Signal(str)


class _InstallBridge(QObject):
    finished = Signal(object, object)   # код завершения | None | текст ошибки, список языков


# ================================================================ панель
class ControlPanel(QWidget):
    def __init__(self, cfg: Config):
        super().__init__(None, Qt.WindowType.Window | Qt.WindowType.WindowStaysOnTopHint)
        self.cfg = cfg
        self.pipeline = Pipeline(cfg)
        self.region: Region | None = Region.from_dict(cfg.get("last_region"))
        self.overlay = OverlayWindow(cfg)
        self.list_window = ListWindow()
        self.selector: SelectionWindow | None = None
        self.pool = QThreadPool.globalInstance()
        self.busy = False
        self._token = 0
        self._last_thumb: np.ndarray | None = None
        self._dirty = False
        self._hotkeys = None
        self._jobs: set[_Job] = set()

        self.setWindowTitle(f"agolate {__version__}")
        self._build_ui()

        self.auto_timer = QTimer(self)
        self.auto_timer.timeout.connect(self._auto_tick)

        self.bridge = _HotkeyBridge()
        self.bridge.fired.connect(self._on_hotkey)
        self._start_hotkeys()

        self._installing = False
        self._install_declined: set[str] = set()
        self.install_bridge = _InstallBridge()
        self.install_bridge.finished.connect(self._on_install_finished)

        if self.region:
            self.overlay.place(self.region)
        self._update_info()
        # Первый запуск: проверяем, есть ли OCR для выбранного языка
        QTimer.singleShot(500, self.check_ocr_language)

    # ------------------------------------------------------------ интерфейс
    def _build_ui(self) -> None:
        hk = self.cfg["hotkeys"]
        self.btn_select = QPushButton(f"Выделить область  ({hk.get('select', '')})")
        self.btn_translate = QPushButton(f"Перевести  ({hk.get('translate', '')})")
        self.btn_toggle = QPushButton(f"Показать/скрыть  ({hk.get('toggle', '')})")
        self.chk_auto = QCheckBox(f"Авто при прокрутке  ({hk.get('auto', '')})")
        self.btn_file = QPushButton("Из файла…")
        self.btn_reset = QPushButton("Новый тайтл")
        self.btn_reset.setToolTip("Забыть контекст и кэш переводов")
        self.btn_settings = QPushButton("Настройки")
        self.info = QLabel()
        self.info.setStyleSheet("color: #888")
        self.status = QLabel("Выделите область чтения.")
        self.status.setWordWrap(True)
        self.status.setMinimumHeight(40)

        self.btn_select.clicked.connect(self.start_selection)
        self.btn_translate.clicked.connect(self.translate_now)
        self.btn_toggle.clicked.connect(self.toggle_overlay)
        self.chk_auto.toggled.connect(self.set_auto)
        self.btn_file.clicked.connect(self.translate_file)
        self.btn_reset.clicked.connect(self.reset_session)
        self.btn_settings.clicked.connect(self.open_settings)

        grid = QGridLayout()
        grid.addWidget(self.btn_select, 0, 0, 1, 2)
        grid.addWidget(self.btn_translate, 1, 0, 1, 2)
        grid.addWidget(self.btn_toggle, 2, 0)
        grid.addWidget(self.chk_auto, 2, 1)
        grid.addWidget(self.btn_file, 3, 0)
        grid.addWidget(self.btn_reset, 3, 1)
        grid.addWidget(self.btn_settings, 4, 0, 1, 2)

        lay = QVBoxLayout(self)
        lay.addLayout(grid)
        lay.addWidget(self.info)
        lay.addWidget(self.status)
        self.setMinimumWidth(380)

    def _update_info(self) -> None:
        c = self.cfg
        src = languages.src(c["source_lang"])["name"].split(" (")[0]
        tgt = languages.tgt(c["target_lang"])["name"]
        tr = TRANSLATORS.get(c["translator"]).title if c["translator"] in TRANSLATORS else c["translator"]
        gl = c.get("glossary") or "нет"
        self.info.setText(f"{src} → {tgt} · {tr} · глоссарий: {gl.removesuffix('.json')}")

    def _set_status(self, text: str, error: bool = False) -> None:
        self.status.setStyleSheet("color: #d9534f" if error else "")
        self.status.setText(text)

    # ------------------------------------------------------------ горячие клавиши
    def _start_hotkeys(self) -> None:
        self._stop_hotkeys()
        try:
            from pynput import keyboard
        except ImportError:
            self._set_status("pynput не установлен — горячие клавиши не работают.", True)
            return
        mapping = {}
        for action, combo in self.cfg["hotkeys"].items():
            if combo:
                mapping[combo] = (lambda a=action: self.bridge.fired.emit(a))
        if not mapping:
            return
        try:
            self._hotkeys = keyboard.GlobalHotKeys(mapping)
            self._hotkeys.daemon = True
            self._hotkeys.start()
        except Exception as e:  # noqa: BLE001
            self._hotkeys = None
            self._set_status(f"Ошибка в горячих клавишах: {e}", True)

    def _stop_hotkeys(self) -> None:
        if self._hotkeys is not None:
            try:
                self._hotkeys.stop()
            except Exception:  # noqa: BLE001
                pass
            self._hotkeys = None

    def _on_hotkey(self, action: str) -> None:
        if action == "select":
            self.start_selection()
        elif action == "translate":
            self.translate_now()
        elif action == "toggle":
            self.toggle_overlay()
        elif action == "auto":
            self.chk_auto.toggle()

    # ------------------------------------------------------------ выделение
    def start_selection(self) -> None:
        if self.selector is not None:
            return
        was_visible = self.overlay.isVisible()
        self.overlay.hide()
        self.selector = SelectionWindow()
        self.selector.selected.connect(self._on_selected)
        self.selector.cancelled.connect(lambda: self._on_selection_end(was_visible))
        self.selector.start()

    def _on_selection_end(self, restore_overlay: bool) -> None:
        self.selector = None
        if restore_overlay and not self.overlay.user_hidden:
            self.overlay.show()

    def _on_selected(self, region: Region) -> None:
        self.selector = None
        self.region = region
        self.cfg["last_region"] = region.to_dict()
        self.cfg.save()
        self.overlay.clear()
        self.overlay.place(region)
        self._last_thumb = None
        QTimer.singleShot(50, self.translate_now)

    # ------------------------------------------------------------ перевод
    def translate_now(self) -> None:
        if self.region is None:
            self.start_selection()
            return
        if self.busy:
            return
        # Если окно перевода не скрыто от снимков — прячем его на время снимка
        if self.overlay.isVisible() and not self.overlay.excluded:
            self.overlay.hide()
            QTimer.singleShot(120, lambda: self._grab_and_run(restore=True))
        else:
            self._grab_and_run(restore=False)

    def _grab_and_run(self, restore: bool) -> None:
        image, k = grab_region(self.region)
        if restore and not self.overlay.user_hidden:
            self.overlay.show()
        self._run(image, k)

    def _run(self, image: Image.Image, k: float, token=None) -> None:
        self.busy = True
        self._token += 1
        self._set_status("Распознаю и перевожу…")
        job = _Job(self.pipeline, image, k, token if token is not None else self._token)
        job.signals.done.connect(self._on_done)
        job.signals.failed.connect(self._on_failed)
        self._jobs.add(job)
        job.setAutoDelete(False)
        self.pool.start(job)

    def _on_done(self, result: PageResult, k: float, token) -> None:
        self.busy = False
        self._jobs.clear()
        if token == "file":
            self._show_file_result(result)
            return
        mode = self.cfg["display_mode"]
        if mode in ("overlay", "both") and self.region:
            self.overlay.set_result(result, k)
        if mode in ("list", "both"):
            self.list_window.set_result(result)
            if not self.list_window.isVisible():
                self.list_window.show()
        self._report(result)

    def _on_failed(self, tb: str) -> None:
        self.busy = False
        self._jobs.clear()
        self._set_status("Непредвиденная ошибка, подробности в error.log", True)
        (ROOT / "error.log").write_text(tb, encoding="utf-8")

    def _report(self, result: PageResult) -> None:
        if result.missing_ocr_lang:
            self._set_status(result.error.split("\n")[0], True)
            self.offer_ocr_install(result.missing_ocr_lang, force=True)
            return
        if result.error:
            if "\n" in result.error:
                QMessageBox.warning(self, "agolate", result.error)
            self._set_status(result.error.split("\n")[0], True)
            return
        n = sum(1 for b in result.bubbles if b.translation)
        t = result.timings
        parts = [f"Облаков: {n}"]
        if result.cache_hits:
            parts.append(f"из кэша: {result.cache_hits}")
        parts.append(f"OCR {t.get('ocr', 0):.2f} с")
        if "translate" in t:
            parts.append(f"перевод {t['translate']:.2f} с")
        if not result.bubbles:
            parts = ["Текст не найден. Попробуйте увеличить «Увеличение перед OCR» в настройках."]
        self._set_status(" · ".join(parts))

    # ------------------------------------------------------------ режим «Авто»
    def set_auto(self, on: bool) -> None:
        if on:
            if self.region is None:
                self.chk_auto.setChecked(False)
                self.start_selection()
                return
            self._last_thumb = None
            self._dirty = True
            self.auto_timer.start(int(self.cfg["auto_interval_ms"]))
            self._set_status("Авто: перевожу, когда страница перестаёт двигаться.")
        else:
            self.auto_timer.stop()
            self._set_status("Авто выключено.")

    def _auto_tick(self) -> None:
        if self.busy or self.region is None or self.selector is not None:
            return
        hidden = False
        if self.overlay.isVisible() and not self.overlay.excluded:
            # Без скрытия от снимков приходится прятать окно — будет лёгкое мигание
            self.overlay.hide()
            QApplication.processEvents()
            hidden = True
        image, k = grab_region(self.region)
        if hidden and not self.overlay.user_hidden:
            self.overlay.show()

        thumb = np.asarray(image.convert("L").resize((64, max(1, int(64 * image.height / max(1, image.width))))),
                           dtype=np.int16)
        changed = self._last_thumb is None or self._last_thumb.shape != thumb.shape \
            or float(np.abs(thumb - self._last_thumb).mean()) > 1.5
        self._last_thumb = thumb
        if changed:
            # Страница двигается: убираем старый перевод и ждём остановки
            self._dirty = True
            self.overlay.clear()
            return
        if self._dirty:
            self._dirty = False
            self._run(image, k)

    # ------------------------------------------------------------ докачка OCR
    def check_ocr_language(self) -> None:
        """Если для языка оригинала нет Windows OCR — предложить установить."""
        if sys.platform != "win32" or self.cfg["ocr_engine"] != "windows":
            return
        try:
            from ..win_setup import missing_ocr_languages
            missing = missing_ocr_languages([self.cfg["source_lang"]])
        except Exception:  # noqa: BLE001
            return  # не удалось проверить — ошибка покажется при первом переводе
        if missing:
            self.offer_ocr_install(missing[0])

    def offer_ocr_install(self, lang: str, force: bool = False) -> None:
        if self._installing or sys.platform != "win32":
            return
        if lang in self._install_declined and not force:
            return
        name = languages.src(lang)["name"].split(" (")[0].lower()
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Question)
        box.setWindowTitle("Нужно докачать распознавание текста")
        box.setText(f"В Windows не установлено распознавание текста для языка: {name}.\n\n"
                    "Программа может докачать его сама (это компонент Windows, несколько МБ).\n"
                    "Windows один раз спросит разрешение администратора — нажмите «Да».\n"
                    "Потом программа перезапустится и права больше не понадобятся.")
        btn_one = box.addButton(f"Установить: {name}", QMessageBox.ButtonRole.AcceptRole)
        btn_all = box.addButton("Установить все языки", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Позже", QMessageBox.ButtonRole.RejectRole)
        box.setDefaultButton(btn_one)
        box.exec()
        clicked = box.clickedButton()
        if clicked is btn_one:
            self._start_install([lang])
        elif clicked is btn_all:
            self._start_install(list(languages.SOURCE_LANGUAGES))
        else:
            self._install_declined.add(lang)
            self._set_status("OCR не установлен — перевод не заработает. "
                             "Окно установки появится при следующем переводе.", True)

    def _start_install(self, langs: list[str]) -> None:
        import threading

        from ..win_setup import install_ocr_languages

        self._installing = True
        self.chk_auto.setChecked(False)
        self._set_status("Устанавливаю распознавание текста… Следите за окном PowerShell.")

        def work():
            try:
                code = install_ocr_languages(langs)
            except OSError as e:
                code = str(e)
            self.install_bridge.finished.emit(code, langs)

        threading.Thread(target=work, daemon=True).start()

    def _on_install_finished(self, code, langs) -> None:
        self._installing = False
        if code is None:
            self._set_status("Установка отменена: Windows не получила разрешение администратора.", True)
            return
        if code == 0:
            QMessageBox.information(self, "Готово",
                                    "Распознавание текста установлено.\nПрограмма сейчас перезапустится.")
            self.restart_app()
            return
        cap = languages.src(langs[0])["name"].split(" (")[0]
        QMessageBox.warning(
            self, "Не получилось установить",
            "Windows не смогла докачать компонент (нужен интернет и работающий Центр обновления).\n\n"
            "Можно установить вручную: Параметры Windows → Время и язык → Язык и регион → "
            f"Добавить язык → {cap}. Отметьте «Распознавание текста» (или оставьте всё по умолчанию).\n"
            "После этого перезапустите программу."
            + (f"\n\nОшибка: {code}" if isinstance(code, str) else ""))
        self._set_status("OCR не установлен.", True)

    def restart_app(self) -> None:
        from PySide6.QtCore import QProcess

        self.cfg.save()
        if getattr(sys, "frozen", False):
            program, args = sys.executable, []
        else:
            program, args = sys.executable, [str(ROOT / "main.py")]
        QProcess.startDetached(program, args, str(ROOT))
        self.close()

    # ------------------------------------------------------------ прочее
    def toggle_overlay(self) -> None:
        if self.overlay.isVisible():
            self.overlay.user_hidden = True
            self.overlay.hide()
            self.list_window.hide()
        else:
            self.overlay.user_hidden = False
            mode = self.cfg["display_mode"]
            if mode in ("overlay", "both") and self.region:
                self.overlay.show()
            if mode in ("list", "both"):
                self.list_window.show()

    def translate_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Картинка", "", "Изображения (*.png *.jpg *.jpeg *.webp *.bmp)")
        if not path or self.busy:
            return
        try:
            image = Image.open(path).convert("RGB")
        except OSError as e:
            self._set_status(f"Не удалось открыть: {e}", True)
            return
        self._run(image, 1.0, token="file")

    def _show_file_result(self, result: PageResult) -> None:
        self.list_window.set_result(result)
        self.list_window.show()
        self.list_window.raise_()
        self._report(result)

    def reset_session(self) -> None:
        self.pipeline.reset_session()
        self.overlay.clear()
        self.list_window.set_result(None)
        self._set_status("Контекст и кэш очищены.")

    def open_settings(self) -> None:
        old_lang, old_engine = self.cfg["source_lang"], self.cfg["ocr_engine"]
        dlg = SettingsDialog(self.cfg, self)
        if dlg.exec():
            if (self.cfg["source_lang"], self.cfg["ocr_engine"]) != (old_lang, old_engine):
                QTimer.singleShot(200, self.check_ocr_language)
            self.pipeline.reload()
            self._start_hotkeys()
            hk = self.cfg["hotkeys"]
            self.btn_select.setText(f"Выделить область  ({hk.get('select', '')})")
            self.btn_translate.setText(f"Перевести  ({hk.get('translate', '')})")
            self.btn_toggle.setText(f"Показать/скрыть  ({hk.get('toggle', '')})")
            self.chk_auto.setText(f"Авто при прокрутке  ({hk.get('auto', '')})")
            if self.auto_timer.isActive():
                self.auto_timer.start(int(self.cfg["auto_interval_ms"]))
            self.overlay.update()
            self._update_info()
            self._set_status("Настройки сохранены.")

    def closeEvent(self, e):
        self.auto_timer.stop()
        self._stop_hotkeys()
        self.overlay.close()
        self.list_window.close()
        super().closeEvent(e)
        QApplication.quit()


# ================================================================ запуск
def _excepthook(exc_type, exc, tb):
    text = "".join(traceback.format_exception(exc_type, exc, tb))
    try:
        (ROOT / "error.log").write_text(text, encoding="utf-8")
    except OSError:
        pass
    sys.__excepthook__(exc_type, exc, tb)


def run_gui() -> int:
    sys.excepthook = _excepthook
    app = QApplication(sys.argv)
    app.setApplicationName("agolate")
    # иконка окна: рядом с main.py или внутри собранного exe
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[2]))
    icon = base / "icon.png"
    if icon.exists():
        app.setWindowIcon(QIcon(str(icon)))
    app.setQuitOnLastWindowClosed(False)
    cfg = Config()
    panel = ControlPanel(cfg)
    panel.show()
    return app.exec()


__all__ = ["ControlPanel", "run_gui"]
