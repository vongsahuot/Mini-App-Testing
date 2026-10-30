package com.kosign.mock.portal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Entity
@Table(name = "mini_apps")
public class MiniApp {
    @Id public String appId;
    public String name;
    public String icon;
    public String badge;
    public boolean enabled;
    public String sectionId;
    public String sectionTitle;
    public int sortOrder;
    public String clientId;

    public interface Repo extends JpaRepository<MiniApp, String> {
        List<MiniApp> findAllByOrderBySortOrder();
    }
}
