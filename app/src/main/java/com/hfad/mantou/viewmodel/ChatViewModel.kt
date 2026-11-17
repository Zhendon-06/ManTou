package com.hfad.mantou.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hfad.mantou.generation.ChatSession
import com.hfad.mantou.generation.ChatSessionRuntime

class ChatViewModel(application: Application) :
    AndroidViewModel(application),
    ChatSession by ChatSessionRuntime.get(application) {

    override fun onCleared() {
        ChatSessionRuntime.get(getApplication()).detachUi()
        super.onCleared()
    }
}
