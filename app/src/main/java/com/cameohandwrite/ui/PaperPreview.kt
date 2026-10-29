package com.cameohandwrite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import com.cameohandwrite.domain.*
import kotlin.math.min

@Composable fun PaperPreview(page:Page,paper:Paper,modifier:Modifier=Modifier,trajectory:Boolean=false) {
    var zoom by remember(page,paper) { mutableFloatStateOf(1f) }
    var pan by remember(page,paper) { mutableStateOf(Offset.Zero) }
    Box(modifier.background(Color(0xffdce1da))) {
        Canvas(Modifier.matchParentSize().pointerInput(page,paper) { detectTransformGestures { _,delta,factor,_ ->
            zoom=(zoom*factor).coerceIn(0.5f,8f);pan+=delta
        } }) {
            if(paper.width<=0 || paper.height<=0 || !paper.width.isFinite() || !paper.height.isFinite()) return@Canvas
            val scale=min((size.width-24)/paper.width.toFloat(),(size.height-24)/paper.height.toFloat())
            val ox=(size.width-paper.width.toFloat()*scale*zoom)/2+pan.x
            val oy=(size.height-paper.height.toFloat()*scale*zoom)/2+pan.y
            clipRect {
                withTransform({translate(ox,oy);scale(scale*zoom,scale*zoom,pivot=Offset.Zero)}) {
                    drawRect(Color.White,size=androidx.compose.ui.geometry.Size(paper.width.toFloat(),paper.height.toFloat()))
                    if(paper.lineSpacing>=1 && paper.firstLine>=0 && paper.lineCount in 1..600) repeat(paper.lineCount) { row ->
                        val y=(paper.firstLine+row*paper.lineSpacing).toFloat()
                        drawLine(Color(0xffbbcfe1),Offset(0f,y),Offset(paper.width.toFloat(),y),0.15f)
                    }
                    val pink=Color(0xffd69a9a)
                    drawLine(pink,Offset(paper.startX.toFloat(),0f),Offset(paper.startX.toFloat(),paper.height.toFloat()),0.2f)
                    drawLine(pink,Offset((paper.width-paper.right).toFloat(),0f),Offset((paper.width-paper.right).toFloat(),paper.height.toFloat()),0.2f)
                    drawLine(pink,Offset(0f,paper.top.toFloat()),Offset(paper.width.toFloat(),paper.top.toFloat()),0.2f)
                    drawLine(pink,Offset(0f,(paper.height-paper.bottom).toFloat()),Offset(paper.width.toFloat(),(paper.height-paper.bottom).toFloat()),0.2f)
                    listOf(0.17,0.5,0.83).forEach { ratio -> drawCircle(Color(0xffdce1da),2.5f,Offset((paper.holes/2).toFloat(),(paper.height*ratio).toFloat())) }
                    var previous:Pt?=null
                    page.strokes.forEachIndexed { index,stroke ->
                        if(stroke.isNotEmpty()) {
                            if(trajectory) previous?.let { a -> val b=stroke.first();drawLine(Color(0x558855cc),Offset(a.x.toFloat(),a.y.toFloat()),Offset(b.x.toFloat(),b.y.toFloat()),0.15f) }
                            val path=Path();stroke.forEachIndexed { i,p -> if(i==0) path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat()) }
                            val color=if(trajectory) Color.hsv((index*11f)%360,0.7f,0.65f) else Color(0xff25382f)
                            drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=0.3f))
                            if(trajectory) drawCircle(Color.Red,0.4f,Offset(stroke.first().x.toFloat(),stroke.first().y.toFloat()))
                            previous=stroke.last()
                        }
                    }
                }
            }
        }
    }
}
