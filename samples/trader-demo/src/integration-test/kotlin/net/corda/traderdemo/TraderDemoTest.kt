/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.traderdemo

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.millis
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.core.*
import net.corda.testing.core.BOC_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.DUMMY_BANK_B_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.InProcess
import net.corda.testing.driver.OutOfProcess
import net.corda.testing.driver.driver
import net.corda.testing.internal.IntegrationTest
import net.corda.testing.internal.IntegrationTestSchemas
import net.corda.testing.internal.toDatabaseSchemaName
import net.corda.testing.node.User
import net.corda.testing.node.internal.poll
import net.corda.traderdemo.flow.CommercialPaperIssueFlow
import net.corda.traderdemo.flow.SellerFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.ClassRule
import org.junit.Test
import java.util.concurrent.Executors

class TraderDemoTest : IntegrationTest() {
    companion object {
        @ClassRule  @JvmField
        val databaseSchemas = IntegrationTestSchemas(*listOf(DUMMY_BANK_A_NAME, DUMMY_BANK_B_NAME, BOC_NAME, DUMMY_NOTARY_NAME)
                .map { it.toDatabaseSchemaName() }.toTypedArray())
    }
    @Test
    fun `runs trader demo`() {
        val demoUser = User("demo", "demo", setOf(startFlow<SellerFlow>(), all()))
        val bankUser = User("user1", "test", permissions = setOf(
                startFlow<CashIssueFlow>(),
                startFlow<CashPaymentFlow>(),
                startFlow<CommercialPaperIssueFlow>(),
                all()))
        driver(DriverParameters(startNodesInProcess = true, inMemoryDB = false, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val (nodeA, nodeB, bankNode) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = BOC_NAME, rpcUsers = listOf(bankUser))
            ).map { (it.getOrThrow() as InProcess) }
            val (nodeARpc, nodeBRpc) = listOf(nodeA, nodeB).map {
                val client = CordaRPCClient(it.rpcAddress)
                client.start(demoUser.username, demoUser.password).proxy
            }
            val nodeBankRpc = let {
                val client = CordaRPCClient(bankNode.rpcAddress)
                client.start(bankUser.username, bankUser.password).proxy
            }

            val clientA = TraderDemoClientApi(nodeARpc)
            val clientB = TraderDemoClientApi(nodeBRpc)
            val clientBank = TraderDemoClientApi(nodeBankRpc)

            val originalACash = clientA.cashCount // A has random number of issued amount
            val expectedBCash = clientB.cashCount + 1
            val expectedPaper = listOf(clientA.commercialPaperCount + 1, clientB.commercialPaperCount)

            clientBank.runIssuer(amount = 100.DOLLARS, buyerName = nodeA.services.myInfo.singleIdentity().name, sellerName = nodeB.services.myInfo.singleIdentity().name)
            clientB.runSeller(buyerName = nodeA.services.myInfo.singleIdentity().name, amount = 5.DOLLARS)

            assertThat(clientA.cashCount).isGreaterThan(originalACash)
            assertThat(clientB.cashCount).isEqualTo(expectedBCash)
            // Wait until A receives the commercial paper
            val executor = Executors.newScheduledThreadPool(1)
            poll(executor, "A to be notified of the commercial paper", pollInterval = 100.millis) {
                val actualPaper = listOf(clientA.commercialPaperCount, clientB.commercialPaperCount)
                if (actualPaper == expectedPaper) Unit else null
            }.getOrThrow()
            executor.shutdown()
            assertThat(clientA.dollarCashBalance).isEqualTo(95.DOLLARS)
            assertThat(clientB.dollarCashBalance).isEqualTo(5.DOLLARS)
        }
    }

    @Test
    fun `Tudor test`() {
        driver(DriverParameters(isDebug = true, startNodesInProcess = false, inMemoryDB = false, extraCordappPackagesToScan = listOf("net.corda.finance"))) {
            val demoUser = User("demo", "demo", setOf(startFlow<SellerFlow>(), all()))
            val bankUser = User("user1", "test", permissions = setOf(all()))
            val (nodeA, nodeB, bankNode) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = BOC_NAME, rpcUsers = listOf(bankUser))
            ).map { (it.getOrThrow() as OutOfProcess) }

            val nodeBRpc = CordaRPCClient(nodeB.rpcAddress).start(demoUser.username, demoUser.password).proxy
            val nodeARpc = CordaRPCClient(nodeA.rpcAddress).start(demoUser.username, demoUser.password).proxy
            val nodeBankRpc = let {
                val client = CordaRPCClient(bankNode.rpcAddress)
                client.start(bankUser.username, bankUser.password).proxy
            }

            TraderDemoClientApi(nodeBankRpc).runIssuer(amount = 100.DOLLARS, buyerName = nodeA.nodeInfo.singleIdentity().name, sellerName = nodeB.nodeInfo.singleIdentity().name)
            val stxFuture = nodeBRpc.startFlow(::SellerFlow, nodeA.nodeInfo.singleIdentity(), 5.DOLLARS).returnValue
            nodeARpc.stateMachinesFeed().updates.toBlocking().first() // wait until initiated flow starts
            nodeA.stop()
            startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser), customOverrides = mapOf("p2pAddress" to nodeA.p2pAddress.toString()))
            stxFuture.getOrThrow()
        }
    }
}
