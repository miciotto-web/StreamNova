package com.example.ui.screens

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
fun SplashScreen(
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF050E1F)),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
     Image(
       painter = painterResource(id = R.drawable.streamnova_logo),
       contentDescription = "StreamNova Logo",
       modifier = Modifier.width(320.dp),
       contentScale = ContentScale.Fit
     )

     Spacer(modifier = Modifier.height(36.dp))

     val infiniteTransition = rememberInfiniteTransition()
     val angle = infiniteTransition.animateFloat(
       initialValue = 0f,
       targetValue = 360f,
       animationSpec = infiniteRepeatable(
         animation = keyframes {
           durationMillis = 1200
           360f at 1200
         },
         repeatMode = RepeatMode.Restart
       )
     ).value

     Canvas(modifier = Modifier.size(36.dp)) {
       val strokeWidth = 3.dp.toPx()
       val radius = (size.minDimension / 2) - (strokeWidth / 2)
       val colorGradient = Brush.sweepGradient(
         colors = listOf(Color(0xFF38BDF8), Color(0xFFC084FC)),
         center = Offset(size.width / 2, size.height / 2)
       )
       drawArc(
         brush = colorGradient,
         startAngle = angle,
         sweepAngle = 270f,
         useCenter = false,
         style = Stroke(strokeWidth)
       )
     }

     Spacer(modifier = Modifier.height(14.dp))

     Text(
      text = "CARICAMENTO IN CORSO...",
      style = TextStyle(
        fontSize = 12.sp,
        letterSpacing = 2.5.sp,
        textAlign = TextAlign.Center,
        color = Color.White.copy(alpha = 0.65f)
      )
    )
  }
}
