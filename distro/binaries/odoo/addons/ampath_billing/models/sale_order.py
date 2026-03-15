from odoo import models, fields, api


class SaleOrder(models.Model):
    _inherit = 'sale.order'

    billing_actions_visible = fields.Boolean(
        compute='_compute_billing_actions_visible',
    )

    @api.depends('state', 'invoice_ids.payment_state')
    def _compute_billing_actions_visible(self):
        for order in self:
            if order.state in ('done', 'cancel'):
                order.billing_actions_visible = False
            elif order.state == 'sale' and order.invoice_ids:
                all_paid = all(
                    inv.payment_state == 'paid'
                    for inv in order.invoice_ids
                    if inv.state == 'posted'
                )
                order.billing_actions_visible = not all_paid
            else:
                order.billing_actions_visible = True
