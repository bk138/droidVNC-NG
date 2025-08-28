/*
 * droidVNC-NG clíent list.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2025 Christian Beier.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */

package net.christianbeier.droidvnc_ng

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest

class ClientList private constructor(
    private val clients: MutableList<Client>
) {

    companion object {
        private val json = Json { explicitNulls = false }

        /**
         * Creates a ClientList from JSON.
         */
        @JvmStatic
        fun fromJson(jsonStr: String?): ClientList {
            return if (jsonStr != null) {
                ClientList(json.decodeFromString(jsonStr))
            } else {
                empty()
            }
        }

        /**
         * Creates an empty ClientList.
         */
        @JvmStatic
        fun empty(): ClientList = ClientList(mutableListOf())

        /**
         * Returns true if the given connection id matches the given client pointer.
         */
        @JvmStatic
        fun isConnectionIdMatchingClient(connectionId: Long, clientPtr: Long): Boolean {
            return hash(clientPtr) == connectionId
        }

        private fun hash(input: Long): Long {
            val bytes = ByteBuffer.allocate(8).putLong(input).array()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return ByteBuffer.wrap(digest, 0, 8).long
        }
    }

    fun toJson(): String = json.encodeToString(clients)

    @Serializable
    class Client private constructor(
        val connectionId: Long?,
        val host: String?,
        val port: Int?,
        val repeaterId: String?,
        val requestId: String?
    ) {
        constructor(
            clientPtr: Long, host: String?, port: Int?, repeaterId: String?, requestId: String?
        ) : this(
            connectionId = if (clientPtr == 0L) {
                null
            } else {
                hash(clientPtr)
            }, host = host, port = port, repeaterId = repeaterId, requestId = requestId
        )
    }

    // Insert or update a client based on connectionId
    fun insertOrUpdate(client: Client) {
        if (client.connectionId != null) {
            val existingIndex = clients.indexOfFirst { it.connectionId == client.connectionId }
            if (existingIndex >= 0) {
                clients[existingIndex] = client // Update existing client
                return
            }
        }
        // Insert new client (either connectionId is zero or not found)
        clients.add(client)
    }

    // Helper to get clients (immutable view)
    fun getClients(): List<Client> = clients.toList()

}
