package com.podly

import android.app.Application
import com.podly.work.FeedRefreshWorker

class PodlyApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        FeedRefreshWorker.schedule(this)
    }
}
