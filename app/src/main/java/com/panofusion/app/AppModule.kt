package com.panofusion.app

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ProjectDatabase = Room.databaseBuilder(context,ProjectDatabase::class.java,"panofusion.db").addMigrations(MIGRATION_1_2).build()
    @Provides @Singleton fun store(@ApplicationContext context: Context, database: ProjectDatabase) = ProjectStore(context,database.dao())
}
