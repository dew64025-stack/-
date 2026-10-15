package com.caizhitong.integration.school;
import org.springframework.stereotype.Component;
@Component public class UnconfiguredSchoolIdentityProvider implements SchoolIdentityProvider {
    public boolean configured(long campusId){return false;}
}
