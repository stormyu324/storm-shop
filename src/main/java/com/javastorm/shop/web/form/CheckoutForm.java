package com.javastorm.shop.web.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CheckoutForm {

    @NotBlank(message = "{checkout.name.required}")
    @Size(max = 50)
    private String customerName;
    @jakarta.validation.constraints.Email
    @Size(max = 254)
    private String email;
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT); }


    @NotBlank(message = "{checkout.phone.required}")
    @Pattern(regexp = "^[0-9+()\\- ]{6,30}$", message = "{checkout.phone.invalid}")
    private String phone;

    @NotBlank(message = "{checkout.address.required}")
    @Size(max = 300)
    private String address;

    @Size(max = 500)
    private String note;
    @Size(max=40) private String couponCode;
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String value) { couponCode=value; }


    /** 寄往国家 / 地区，ISO 两位代码。 */
    @NotBlank(message = "{checkout.country.required}")
    @Pattern(regexp = "^[A-Za-z]{2}$", message = "{checkout.country.required}")
    private String country;

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country == null ? null : country.trim().toUpperCase(java.util.Locale.ROOT);
    }

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
