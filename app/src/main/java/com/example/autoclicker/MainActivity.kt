package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    companion object { const val DEFAULT_STEPS = "上传,相册,TAP:540:600,确认" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        col.addView(TextView(this).apply {
            text = "使用步骤：\n1. 点下方按钮，在无障碍里开启「自动点击器」\n" +
                "2. 编辑宏步骤并保存\n3. 打开目标App，点悬浮的「宏」按钮运行（再点一次停止，可拖动）\n\n" +
                "步骤写法（逗号分隔）：\n文字 = 点击含该文字的按钮\nTAP:x:y = 点击屏幕坐标\nWAIT:毫秒 = 等待"
        })
        val et = EditText(this).apply { setText(prefs.getString("steps", DEFAULT_STEPS)) }
        col.addView(et)
        col.addView(Button(this).apply {
            text = "保存步骤"
            setOnClickListener { prefs.edit().putString("steps", et.text.toString()).apply(); Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() }
        })
        col.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
