package com.podly

import android.app.Application
import com.podly.work.FeedRefreshWorker
import com.podly.work.RadioPoolWorker

class PodlyApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        FeedRefreshWorker.schedule(this)
        RadioPoolWorker.schedule(this)
    }
}
