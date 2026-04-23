package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val appModule = module {
    // Application
    single { get<Context>() as MainApplication }

    // Coroutine Scope
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
