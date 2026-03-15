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

    def _prepare_invoice_line(self, **optional_values):
        """Map custom fields from SO line to Invoice line."""
        res = super(SaleOrderLine, self)._prepare_invoice_line(**optional_values)
        res.update({
            'claim_status': self.claim_status,
            'insurance_provider_id': self.insurance_provider_id.id,
            'fhir_claim_id': self.fhir_claim_id,
        })
        return res

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
        """Create a down payment for selected lines."""
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
            },
            'target': 'new',
            'type': 'ir.actions.act_window',
        }