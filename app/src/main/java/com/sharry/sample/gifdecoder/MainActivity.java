package com.sharry.sample.gifdecoder;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sharry.sample.gifdecoder.extension.GlideApp;

/**
 * @author Sharry <a href="xiaoyu.zhu@1hai.cn">Contact me.</a>
 * @version 1.0
 * @since 2019-12-23 15:24
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final ImageView ivDisplay = findViewById(R.id.iv_display);
        findViewById(R.id.btn_album).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 使用 GIFLIB  + FrameSequenceDrawable 加载
                GlideApp.with(MainActivity.this)
                        .asGif2()
                        .load(R.drawable.app_gif_shared_element)
                        .into(ivDisplay);
                // 使用 Glide 原生加载
//                Glide.with(MainActivity.this)
//                        .asGif()
//                        .load(R.drawable.app_gif_shared_element)
//                        .into(ivDisplay);
                // 可以打开 Profiler 查看内存使用情况
            }
        });
    }
}
