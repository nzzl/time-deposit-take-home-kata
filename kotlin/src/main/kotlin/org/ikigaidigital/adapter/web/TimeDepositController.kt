package org.ikigaidigital.adapter.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.ikigaidigital.application.service.TimeDepositService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The inbound REST adapter. It exposes **exactly the two endpoints** the brief allows and nothing
 * else (SPEC.md §2, E6):
 *
 * - `GET  /time-deposits`                 — retrieve all deposits with their withdrawals
 * - `POST /time-deposits/balance-updates` — recalculate and persist every balance
 *
 * `POST` is used for the update because the operation is not idempotent: it compounds interest on
 * each call (SPEC.md A12), so `PUT` — which implies idempotency — would misrepresent it. The path is
 * a noun sub-collection ("balance updates") rather than a verb, and the handler returns the updated
 * deposits so a caller sees the result of the run.
 */
@RestController
@RequestMapping("/time-deposits")
@Tag(name = "Time Deposits", description = "Retrieve time deposits and apply interest to their balances")
class TimeDepositController(private val service: TimeDepositService) {

    @GetMapping
    @Operation(
        summary = "List all time deposits",
        description = "Returns every time deposit with its nested withdrawals."
    )
    fun getAllDeposits(): List<TimeDepositResponse> =
        service.getAllDeposits().map { it.toResponse() }

    @PostMapping("/balance-updates")
    @Operation(
        summary = "Apply interest to all balances",
        description = "Applies one month's interest to every deposit, persists the new balances, and " +
            "returns the updated deposits. Not idempotent: each call compounds."
    )
    fun updateAllBalances(): List<TimeDepositResponse> =
        service.recalculateBalances().map { it.toResponse() }
}
