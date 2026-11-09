package com.demo.transfer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dkn_beneficiary")
public class Beneficiary {

    @Id
    private Long id;
    private String buid;
    private String currency;
}
