/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.hash

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Object responsible for hashing strings using the SHA-512 algorithm.
 *
 * Credits to https://stackoverflow.com/a/66733767
 */
object Sha512 {

    val md = MessageDigest.getInstance("SHA-512")

    fun hash(input: ByteArray): String {
        val messageDigest = md.digest(input)

        // Convert byte array into signum representation
        val no = BigInteger(1, messageDigest)

        // Convert message digest into hex value
        var hashtext: String = no.toString(16)

        // Add preceding 0s to make it 128 chars long
        while (hashtext.length < 128) {
            hashtext = "0$hashtext"
        }

        // return the HashText
        return hashtext
    }
}
