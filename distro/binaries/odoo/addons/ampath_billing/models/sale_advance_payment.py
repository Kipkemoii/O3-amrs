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
        selected_line_ids = self.env.context.get('ampath_selected_line_ids', [])

        result = super().create_invoices()

        # --- Rename the DP line and its invoice line ---
        if product_names and self.advance_payment_method == 'fixed':
            sale_orders = self.env['sale.order'].browse(
                self._context.get('active_ids', [])
            )
            dp_label = _("Down Payment for: %s") % product_names
            for order in sale_orders:
                dp_lines = order.order_line.filtered(lambda l: l.is_downpayment)
                if not dp_lines:
                    continue
                newest_dp = dp_lines.sorted('id', reverse=True)[0]
                newest_dp.with_context(_ampath_dp_sync=True).name = dp_label
                for inv_line in newest_dp.invoice_lines:
                    inv_line.with_context(check_move_validity=False).name = dp_label

        # --- Link selected product lines to the new DP line ---
        if selected_line_ids:
            selected_lines = self.env['sale.order.line'].browse(selected_line_ids).exists()
            for order in selected_lines.mapped('order_id'):
                newest_dp = (
                    order.order_line
                    .filtered('is_downpayment')
                    .sorted('id', reverse=True)[:1]
                )
                if not newest_dp:
                    continue
                order_lines = selected_lines.filtered(lambda l: l.order_id == order)
                order_lines.with_context(_ampath_dp_sync=True).write(
                    {'downpayment_line_id': newest_dp.id}
                )

        return result
