from odoo import models, fields, api, _
from odoo.exceptions import UserError
import requests


class SaleOrderLine(models.Model):
    _inherit = 'sale.order.line'

    claim_status = fields.Selection([
        ('draft', 'Not Claimed'),
        ('submitted', 'Submitted'),
        ('approved', 'Approved'),
        ('rejected', 'Rejected')
    ], default='draft', string="FHIR Status", copy=False)

    insurance_provider_id = fields.Many2one('res.partner', string="Insurance Payer")
    fhir_claim_id = fields.Char("FHIR ID", copy=False)
    billing_actions_visible = fields.Boolean(
        related='order_id.billing_actions_visible',
    )
    selected = fields.Boolean(string="✓", default=False, copy=False)

    downpayment_line_id = fields.Many2one(
        'sale.order.line',
        string="Down Payment",
        domain=[('is_downpayment', '=', True)],
        copy=False,
        ondelete='set null',
        index=True,
        help="The down payment line that covers this item.",
    )
    is_line_locked = fields.Boolean(
        compute='_compute_is_line_locked',
        string="Locked",
        help="True when the linked down payment invoice has been paid.",
    )
    lock_indicator = fields.Char(
        compute='_compute_lock_indicator',
        string="",
        help="🔒 = covered by a paid down payment  |  ⏳ = down payment in progress",
    )

    @api.depends('is_line_locked', 'downpayment_line_id')
    def _compute_lock_indicator(self):
        for line in self:
            if line.is_line_locked:
                line.lock_indicator = '🔒'
            elif line.downpayment_line_id:
                line.lock_indicator = '⏳'
            else:
                line.lock_indicator = ''

    @api.depends(
        'downpayment_line_id',
        'downpayment_line_id.invoice_lines.move_id.state',
        'downpayment_line_id.invoice_lines.move_id.payment_state',
    )
    def _compute_is_line_locked(self):
        for line in self:
            if not line.downpayment_line_id or line.is_downpayment or line.display_type:
                line.is_line_locked = False
                continue
            dp_invoices = line.downpayment_line_id.invoice_lines.mapped('move_id')
            line.is_line_locked = any(
                inv.state == 'posted' and inv.payment_state in ('paid', 'in_payment')
                for inv in dp_invoices
            )

    # ------------------------------------------------------------------
    # Write guard: block edits on paid lines; sync draft DP invoice amount
    # ------------------------------------------------------------------

    _PRICE_FIELDS = frozenset({
        'product_id', 'product_template_id',
        'product_uom_qty', 'price_unit', 'discount',
    })

    def write(self, vals):
        # Skip lock/sync logic when called internally during DP sync
        if self.env.context.get('_ampath_dp_sync'):
            return super().write(vals)

        has_price_change = bool(self._PRICE_FIELDS & set(vals))

        if has_price_change:
            locked = self.filtered(
                lambda l: not l.is_downpayment and not l.display_type and l.is_line_locked
            )
            if locked:
                names = ', '.join(
                    l.product_id.name or l.name or '?'
                    for l in locked
                )
                raise UserError(_(
                    "Cannot modify the following line(s) — they are covered by a paid "
                    "down payment:\n%s"
                ) % names)

        result = super().write(vals)

        if has_price_change:
            self.env.flush_all()
            lines_with_unpaid_dp = self.filtered(
                lambda l: (
                    not l.is_downpayment
                    and not l.display_type
                    and l.downpayment_line_id
                    and not l.is_line_locked
                )
            )
            seen_dp_ids = set()
            for line in lines_with_unpaid_dp:
                dp_id = line.downpayment_line_id.id
                if dp_id not in seen_dp_ids:
                    line._sync_downpayment_invoice()
                    seen_dp_ids.add(dp_id)

        return result

    def _sync_downpayment_invoice(self):
        """Recalculate the linked down payment to match the sum of all covered lines."""
        dp = self.downpayment_line_id
        if not dp:
            return
        linked = self.search([('downpayment_line_id', '=', dp.id)])
        new_total = sum(l.price_total for l in linked)
        if abs((dp.price_unit or 0.0) - new_total) < 0.01:
            return
        # Update the DP sale.order.line (no lock check — use internal context)
        dp.with_context(_ampath_dp_sync=True).write({'price_unit': new_total})
        # Update any draft invoice lines for this DP
        for inv_line in dp.invoice_lines:
            if inv_line.move_id.state == 'draft':
                inv_line.with_context(
                    check_move_validity=False,
                    _ampath_dp_sync=True,
                ).price_unit = new_total

    # ------------------------------------------------------------------
    # Invoice preparation
    # ------------------------------------------------------------------

    def _prepare_invoice_line(self, **optional_values):
        """Map custom fields from SO line to Invoice line."""
        res = super()._prepare_invoice_line(**optional_values)
        res.update({
            'claim_status': self.claim_status,
            'insurance_provider_id': self.insurance_provider_id.id,
            'fhir_claim_id': self.fhir_claim_id,
        })
        return res

    # ------------------------------------------------------------------
    # Bulk actions
    # ------------------------------------------------------------------

    def action_bulk_waive(self):
        """Apply 100% discount to selected lines."""
        for line in self:
            line.write({'discount': 100.0})

    def action_bulk_fhir_claim(self):
        """Simulate sending FHIR bundle for selected lines."""
        for line in self:
            if not line.insurance_provider_id:
                raise UserError(_("Select an Insurance Payer for %s first.") % line.name)
            # Placeholder for actual FHIR logic
            line.write({'claim_status': 'submitted', 'fhir_claim_id': 'FHIR-SO-TMP'})

    def action_bulk_individual_payment(self):
        """Open the down payment wizard for the selected lines."""
        total_to_pay = sum(line.price_total for line in self)
        summary_lines = []
        for line in self:
            product = line.product_id.name or line.name or _('Unknown')
            summary_lines.append(
                "%s  ×%g  %s" % (product, line.product_uom_qty, line.price_total)
            )
        product_names = ', '.join(
            name for name in self.mapped('product_id.name') if name
        )
        view_id = self.env.ref(
            'ampath_billing.view_sale_advance_payment_inv_ampath'
        ).id
        return {
            'name': _('Pay Selected Items'),
            'res_model': 'sale.advance.payment.inv',
            'view_mode': 'form',
            'view_id': view_id,
            'context': {
                'active_ids': self.mapped('order_id').ids,
                'default_advance_payment_method': 'fixed',
                'default_fixed_amount': total_to_pay,
                'ampath_selected_products': product_names,
                'ampath_product_summary_detail': '\n'.join(summary_lines),
                # IDs of the product lines being paid — used after wizard confirms
                'ampath_selected_line_ids': self.ids,
            },
            'target': 'new',
            'type': 'ir.actions.act_window',
        }
