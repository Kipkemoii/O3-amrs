from odoo import models, fields, api, _
from odoo.exceptions import UserError


class SaleOrderLineWizardLine(models.TransientModel):
    """Individual line in the wizard with a selectable checkbox."""
    _name = 'sale.order.line.wizard.line'
    _description = 'Wizard Order Line'

    wizard_id = fields.Many2one('sale.order.line.wizard', required=True, ondelete='cascade')
    order_line_id = fields.Many2one('sale.order.line', string="Order Line", required=True)
    selected = fields.Boolean(string="Select", default=True)

    # Display fields (related, read-only)
    product_id = fields.Many2one(related='order_line_id.product_id', string="Product")
    name = fields.Text(related='order_line_id.name', string="Description")
    product_uom_qty = fields.Float(related='order_line_id.product_uom_qty', string="Qty")
    price_unit = fields.Float(related='order_line_id.price_unit', string="Unit Price")
    discount = fields.Float(related='order_line_id.discount', string="Discount (%)")
    price_subtotal = fields.Monetary(related='order_line_id.price_subtotal', string="Subtotal")
    currency_id = fields.Many2one(related='order_line_id.currency_id')
    insurance_provider_id = fields.Many2one(
        related='order_line_id.insurance_provider_id', string="Insurance Payer"
    )
    claim_status = fields.Selection(related='order_line_id.claim_status', string="FHIR Status")


class SaleOrderLineWizard(models.TransientModel):
    _name = 'sale.order.line.wizard'
    _description = 'Bulk Actions on Sale Order Lines'

    order_id = fields.Many2one('sale.order', string="Sale Order", required=True)
    line_ids = fields.One2many(
        'sale.order.line.wizard.line', 'wizard_id', string="Order Lines",
    )

    @api.model
    def default_get(self, fields_list):
        res = super().default_get(fields_list)
        active_id = self.env.context.get('active_id')
        if active_id:
            order = self.env['sale.order'].browse(active_id)
            product_lines = order.order_line.filtered(lambda l: not l.display_type)
            res['order_id'] = active_id
            res['line_ids'] = [
                (0, 0, {'order_line_id': line.id, 'selected': True})
                for line in product_lines
            ]
        return res

    def _get_selected_lines(self):
        """Return the actual sale.order.line records that are checked."""
        selected = self.line_ids.filtered(lambda l: l.selected)
        if not selected:
            raise UserError(_("Please select at least one order line."))
        return selected.mapped('order_line_id')

    def action_waive(self):
        lines = self._get_selected_lines()
        lines.action_bulk_waive()
        return {'type': 'ir.actions.client', 'tag': 'reload'}

    def action_claim(self):
        lines = self._get_selected_lines()
        lines.action_bulk_fhir_claim()
        return {'type': 'ir.actions.client', 'tag': 'reload'}

    def action_pay(self):
        lines = self._get_selected_lines()
        return lines.action_bulk_individual_payment()
