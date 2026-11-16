package com.example.utils

import com.example.data.CardsCharset
import com.example.data.Design
import com.example.data.ElementStyle

object A4 {
    const val WIDTH = 210f
    const val HEIGHT = 297f
}

fun cardSize(design: Design): Pair<Float, Float> {
    return if (!design.autoFit) {
        Pair(design.cardWidth, design.cardHeight)
    } else {
        val usableW = A4.WIDTH - design.pageMargin * 2 - design.marginH * (design.columns - 1)
        val usableH = A4.HEIGHT - design.pageMargin * 2 - design.marginV * (design.rows - 1)
        Pair(
            maxOf(10f, usableW / maxOf(1, design.columns)),
            maxOf(6f, usableH / maxOf(1, design.rows))
        )
    }
}


fun defaultDesign(id: String, name: String = "Default_Design"): Design {
    return Design(
        id = id,
        name = name,
        columns = 3,
        rows = 17,
        marginH = 0f,
        marginV = 0f,
        autoFit = true,
        pageMargin = 5f,
        cardWidth = 65f,
        cardHeight = 15f,
        showUsername = true,
        username = ElementStyle(show = true, x = 4f, y = 4f, fontSize = 11f, bold = true),
        title = ElementStyle(show = false, y = 2f, fontSize = 8f, text = "MK NETCRAFT"),
        password = ElementStyle(show = false, x = 34f, y = 4f, fontSize = 10f),
        borderOn = true,
        borderSize = 0.35f,
        borderColor = "#000000",
        barcodeOn = false,
        barcodeWidth = 18f,
        barcodeHeight = 8f,
        barcodeX = 44f,
        barcodeY = 3f,
        digits = 10,
        charset = CardsCharset.NUMBERS_ONLY,
        moveStep = 0.5f
    )
}

fun withDesignDefaults(design: Design): Design {
    return design
}
