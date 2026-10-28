package com.chamika.dashtune.signin

import android.content.ComponentName
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.chamika.dashtune.DashTuneMusicService
import com.chamika.dashtune.DashTuneSessionCallback.Companion.LOGIN_COMMAND
import com.chamika.dashtune.R
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SignInActivity : AppCompatActivity() {

    private lateinit var viewModel: SignInViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_sign_in)

        val container = findViewById<View>(R.id.sign_in_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(container)

        viewModel = ViewModelProvider(this)[SignInViewModel::class.java]

        viewModel.loggedIn.observe(this) { loggedIn ->
            if (loggedIn == true) {
                val service = ComponentName(applicationContext, DashTuneMusicService::class.java)
                val future = MediaController.Builder(
                    applicationContext,
                    SessionToken(applicationContext, service)
                ).buildAsync()

                Futures.addCallback(future, object : FutureCallback<MediaController> {
                    override fun onSuccess(controller: MediaController?) {
                        controller?.sendCustomCommand(SessionCommand(LOGIN_COMMAND, Bundle()), Bundle())
                        finish()
                    }

                    override fun onFailure(t: Throwable) {
                        finish()
                    }
                }, MoreExecutors.directExecutor())
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.sign_in_container, ServerSignInFragment())
                .commit()
        }
    }
}
