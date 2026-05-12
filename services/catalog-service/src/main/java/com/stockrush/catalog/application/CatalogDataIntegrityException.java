package com.stockrush.catalog.application;

public class CatalogDataIntegrityException extends RuntimeException {

    public CatalogDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
