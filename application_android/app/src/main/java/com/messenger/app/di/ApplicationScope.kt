package com.messenger.app.di

import javax.inject.Qualifier

/**
 * Marks the application-lifetime CoroutineScope, distinguishing it from any
 * other CoroutineScope binding. Work in this scope survives ViewModel clearing.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
