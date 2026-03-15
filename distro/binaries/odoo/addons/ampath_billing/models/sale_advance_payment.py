from odoo import models, fields, api, _


class SaleAdvancePaymentInv(models.TransientModel):
    _inherit = 'sale.advance.payment.inv'

    ampath_product_summary = fields.Text(
        string="Items Being Paid", readonly=True,
    )

    @api.model
    def default_get(self, fields_list):
        res = super().default_get(fields_list)
        detail = self.env.context.get('ampath_product_summary_detail')
        if detail:
            res['ampath_product_summary'] = detail
        else:
            summary = self.env.context.get('ampath_selected_products')
            if summary:
                res['ampath_product_summary'] = summary
        return res

    def create_invoices(self):
        product_names = self.env.context.get('ampath_selected_products')
        result = super().create_invoices()

        if not product_names or self.advance_payment_method != 'fixed':
            return result

        sale_orders = self.env['sale.order'].browse(
            self._context.get('active_ids', [])
        )
        dp_label = _("Down Payment for: %s") % product_names
        for order in sale_orders:
            dp_lines = order.order_line.filtered(lambda l: l.is_downpayment)
            if not dp_lines:
                continue
            newest_dp = dp_lines.sorted('id', reverse=True)[0]
            newest_dp.name = dp_label
            for inv_line in newest_dp.invoice_lines:
                inv_line.with_context(check_move_validity=False).name = dp_label

        return result
