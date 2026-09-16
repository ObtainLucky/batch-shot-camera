/**
 * ShutterSoundPlayer.kt - 快门声播放
 *
 * 用系统提供的拍摄音效（MediaActionSound），不打包音频资源。
 *
 * 说明：MediaActionSound 需要先 load 才能 play，所以实例保持常驻、
 * 只加载一次；播放后立刻 release 会把声音掐掉。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.sound

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 快门声播放器
 *
 * @param context 应用上下文
 */
@Singleton
class ShutterSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ShutterSoundPlayer"                  // 日志标签
    }

    /** 常驻的播放器实例（load 一次，后续直接 play） */
    private var sound: MediaActionSound? = null

    /**
     * 播放快门声
     *
     * 静音/振动模式下不播放 —— 用户把手机调成静音时不该被相机打断。
     */
    fun play() {
        if (isSilentMode()) {
            Log.d(TAG, "play: 当前为静音/振动模式，跳过快门声")
            return
        }
        try {
            val player = sound ?: MediaActionSound().also {
                it.load(MediaActionSound.SHUTTER_CLICK)
                sound = it
            }
            player.play(MediaActionSound.SHUTTER_CLICK)
            Log.d(TAG, "play: 已播放快门声")
        } catch (e: Exception) {
            Log.w(TAG, "play: 快门声播放失败", e)
        }
    }

    /**
     * 当前是否处于静音或振动模式
     */
    private fun isSilentMode(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val ringerMode = audioManager?.ringerMode
            ringerMode != AudioManager.RINGER_MODE_NORMAL
        } catch (e: Exception) {
            false
        }
    }
}
