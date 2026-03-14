from odoo import models, fields, api, _
from odoo.exceptions import UserError


class SaleOrderLineWizard(models.TransientModel):
    _name = 'sale.order.line.wizard'
    _description = 'Bulk Actions on Sale Order Lines'

    order_id = fields.Many2one('sale.order', string="Sale Order", required=True)
    line_ids = fields.Many2many(
        'sale.order.line',
        string="Order Lines",
    )

    @api.model
    def default_get(self, fields_list):
        res = super().default_get(fields_list)
        active_id = self.env.context.get('active_id')
        if active_id:
            order = self.env['sale.order'].browse(active_id)
            # Only include product lines – skip sections and notes
            product_lines = order.order_line.filtered(
                lambda l: not l.display_type
            )
            res['order_id'] = active_id
            res['line_ids'] = [(6, 0, product_lines.ids)]
        return res

    def action_waive(self):
        """Apply 100% discount to selected lines."""
        if not self.line_ids:
            raise UserError(_("Please select at least one order line."))
        self.line_ids.action_bulk_waive()
        return {'type': 'ir.actions.client', 'tag': 'reload'}

    def action_claim(self):
        """Submit FHIR claims for selected lines."""
        if not self.line_ids:
            raise UserError(_("Please select at least one order line."))
        self.line_ids.action_bulk_fhir_claim()
        return {'type': 'ir.actions.client', 'tag': 'reload'}

    def action_pay(self):
        """Create payment for selected lines."""
        if not self.line_ids:
            raise UserError(_("Please select at least one order line."))
        return self.line_ids.action_bulk_individual_payment()
