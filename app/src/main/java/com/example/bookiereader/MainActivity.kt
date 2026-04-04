package com.example.bookiereader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.bookiereader.ui.theme.BookieReaderTheme
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        var keepSplash = true
        splashScreen.setKeepOnScreenCondition { keepSplash }

        setContent {
            val viewModel: BookViewModel = viewModel()
            val themeMode = viewModel.themeMode
            val darkTheme = when (themeMode) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }

            BookieReaderTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "splash") {
                        composable("splash") {
                            LaunchedEffect(Unit) {
                                keepSplash = false
                                delay(1200) // Minimum splash duration
                                if (viewModel.baseUrl.isNotEmpty()) {
                                    navController.navigate("bookList") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("login") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            }
                            CustomSplashScreen()
                        }
                        composable("login") {
                            LoginScreen(
                                viewModel = viewModel,
                                onLoginSuccess = {
                                    navController.navigate("bookList") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("bookList") {
                            BookListScreen(
                                viewModel = viewModel,
                                onOpenBook = {
                                    navController.navigate("reader")
                                },
                                onLogout = {
                                    viewModel.logout {
                                        navController.navigate("login") {
                                            popUpTo("bookList") { inclusive = true }
                                        }
                                    }
                                },
                                onSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("reader") {
                            ReaderScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_bookie_logo),
                contentDescription = stringResource(R.string.app_icon_desc),
                modifier = Modifier.size(108.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        
        Text(
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    viewModel: BookViewModel,
    onLoginSuccess: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_bookie_logo),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // Group the server settings separately
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.server_settings_header),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.server_url_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri, 
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false
                    ),
                    singleLine = true
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        // Wrap credentials in a Box with explicit semantics to isolate them
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { 
                    // No specific content type here to avoid confusing the manager
                }
        ) {
            Text(
                text = stringResource(R.string.credentials_header),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text, 
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password, 
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (viewModel.isLoading) {
            CircularProgressIndicator(color = colorScheme.primary)
        } else {
            Button(
                onClick = { 
                    viewModel.connect(url, username, password, onLoginSuccess)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
            ) {
                Text(stringResource(R.string.connect_button), fontWeight = FontWeight.Bold)
            }
        }
    }
}
