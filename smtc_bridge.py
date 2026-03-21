from datetime import timedelta
from typing import Any, Optional

try:
    from winrt.windows.media import (
        MediaPlaybackStatus,
        MediaPlaybackType,
        SystemMediaTransportControlsTimelineProperties,
    )
    from winrt.windows.media.playback import BackgroundMediaPlayer

    _WINRT_AVAILABLE = True
except Exception:
    _WINRT_AVAILABLE = False


class SmtcBridge:
    def __init__(self, logger):
        self.logger = logger
        self.enabled = False
        self.smtc = None
        self.updater = None
        self.duration_ms = 0

    def initialize(self) -> None:
        if not _WINRT_AVAILABLE:
            self.logger.warning("SMTC disabled: winrt package not available.")
            return

        try:
            player = BackgroundMediaPlayer.current
            self.smtc = player.system_media_transport_controls
            self.updater = self.smtc.display_updater

            self.smtc.is_enabled = True
            self.smtc.is_play_enabled = True
            self.smtc.is_pause_enabled = True
            self.smtc.is_next_enabled = True
            self.smtc.is_previous_enabled = True
            self.smtc.is_stop_enabled = True
            self.smtc.playback_status = MediaPlaybackStatus.CLOSED

            self.updater.type = MediaPlaybackType.MUSIC
            self.updater.update()

            self.enabled = True
            self.logger.info("SMTC ready")
        except Exception as e:
            self.logger.warning(f"SMTC init failed: {e}")

    def update_from_event(self, event: dict[str, Any]) -> None:
        if not self.enabled or not self.smtc or not self.updater:
            return

        event_type = event.get("event")

        try:
            if event_type == "metadata":
                title = event.get("title") or ""
                artist = event.get("artist") or ""
                album = event.get("album") or ""
                self.duration_ms = int(event.get("duration", 0) or 0)

                self.updater.type = MediaPlaybackType.MUSIC
                if title:
                    self.updater.music_properties.title = title
                    self.updater.music_properties.artist = artist
                    self.updater.music_properties.album_title = album
                self.updater.update()

                self._update_timeline(position_ms=0)

            elif event_type == "playback":
                state = event.get("state", "None") or "None"
                position_ms = int(event.get("position", 0) or 0)

                if state == "Stopped":
                    self.smtc.playback_status = MediaPlaybackStatus.STOPPED
                elif state == "Paused":
                    self.smtc.playback_status = MediaPlaybackStatus.PAUSED
                elif state == "Playing":
                    self.smtc.playback_status = MediaPlaybackStatus.PLAYING
                elif state == "Changing":
                    self.smtc.playback_status = MediaPlaybackStatus.CHANGING
                else:
                    self.smtc.playback_status = MediaPlaybackStatus.CLOSED

                self._update_timeline(position_ms=position_ms)

            elif event_type == "session":
                if not event.get("package"):
                    self.clear()
        except Exception as e:
            self.logger.debug(f"SMTC update failed: {e}")

    def _update_timeline(self, position_ms: int) -> None:
        if not self.smtc or self.duration_ms <= 0:
            return

        try:
            tl = SystemMediaTransportControlsTimelineProperties()
            tl.start_time = timedelta()
            tl.min_seek_time = timedelta()
            tl.position = timedelta(milliseconds=max(0, position_ms))
            tl.max_seek_time = timedelta(milliseconds=self.duration_ms)
            tl.end_time = timedelta(milliseconds=self.duration_ms)
            self.smtc.update_timeline_properties(tl)
        except Exception as e:
            self.logger.debug(f"SMTC timeline update failed: {e}")

    def clear(self) -> None:
        if not self.enabled or not self.smtc or not self.updater:
            return

        try:
            self.smtc.playback_status = MediaPlaybackStatus.CLOSED
            self.updater.clear_all()
            self.updater.update()
        except Exception:
            pass