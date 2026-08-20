package com.jason.notifyline.lineuser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LineUserRepository extends JpaRepository<LineUser, String> {

    /** target=OWNER 的收件人解析。 */
    List<LineUser> findByOwnerTrueAndStatus(LineUserStatus status);

    /** target=ALL 的收件人解析。 */
    List<LineUser> findByStatus(LineUserStatus status);

    List<LineUser> findByLineUserIdInAndStatus(List<String> lineUserIds, LineUserStatus status);

    long countByStatus(LineUserStatus status);

    /** 儀表板：目前的 owner 數。 */
    long countByOwnerTrueAndStatus(LineUserStatus status);
}
