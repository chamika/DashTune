package com.chamika.dashtune.signin

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_sign_in)

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

        supportFragmentManager.beginTransaction()
            .add(R.id.sign_in_container, ServerSignInFragment())
            .commit()
    }
}
