package com.javastorm.shop.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CheckoutForm {

    @NotBlank(message = "{checkout.name.required}")
    @Size(max = 50)
    private String customerName;

    @NotBlank(message = "{checkout.phone.required}")
    @Pattern(regexp = "^[0-9+()\\- ]{6,30}$", message = "{checkout.phone.invalid}")
    private String phone;

    @NotBlank(message = "{checkout.address.required}")
    @Size(max = 300)
    private String address;

    @Size(max = 500)
    private String note;

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
