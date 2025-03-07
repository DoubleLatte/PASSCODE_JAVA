module com.ddlatte.encryption {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.kordamp.ikonli.javafx;

    opens com.ddlatte.encryption to javafx.fxml;
    exports com.ddlatte.encryption;
}