package com.nesscurie.utils.system

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.*

class InputEventController() {

    private data class SendInfo(val keyCode: Int, val downTime: Long)

    @Deprecated("InputManager injectInputEvent must need framework.jar,don't need context if have framework.jar")
    constructor(context: Context) : this()

    private var lastKeyInfo: SendInfo? = null

    /**
     * 7.1这样调用就能生效
     */
    fun sendKeyEvent(keyCode: Int) {
        val now = System.currentTimeMillis()
        val keyEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, 0)
        InputManager.getInstance().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    /**
     * 9.0需要这样处理才生效,目前px30有效,8953有问题
     */
    fun setKeyEventListener(view: View, keyCode: Int) {
        view.isClickable = true
        view.setOnTouchListener(object : View.OnTouchListener {
            var downTime: Long = 0L

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastKeyInfo?.run {
                            sendEvent(this.keyCode, KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED, this.downTime)
                            lastKeyInfo = null
                        }
                        downTime = System.currentTimeMillis()
                        sendEvent(keyCode, KeyEvent.ACTION_DOWN, 0, downTime, System.currentTimeMillis())
                        lastKeyInfo = SendInfo(keyCode, downTime)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        sendEvent(keyCode, KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED, downTime)
                        lastKeyInfo = null
                    }
                    MotionEvent.ACTION_UP -> {
                        sendEvent(keyCode, KeyEvent.ACTION_UP, 0, downTime)
                        lastKeyInfo = null
                    }
                }
                return false
            }
        })
    }

    fun sendEvent(keyCode: Int, action: Int, flags: Int, downTime: Long) {
        sendEvent(keyCode, action, flags, downTime, SystemClock.uptimeMillis())
    }

    fun sendEvent(keyCode: Int, action: Int, flags: Int, downTime: Long, `when`: Long) {
        val repeatCount = if (flags and KeyEvent.FLAG_LONG_PRESS != 0) 1 else 0
        val ev = KeyEvent(downTime, `when`, action, keyCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags or KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD)
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }
}