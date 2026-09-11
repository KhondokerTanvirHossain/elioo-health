package com.elioo.healthcare.aws.textract.model;

import java.util.List;

/**
 * Serializable representation of a Textract block relationship.
 * This replaces the AWS SDK's Relationship class for JSON serialization.
 *
 * @param type The type of relationship (CHILD, VALUE, ANSWER, etc.)
 * @param ids  List of block IDs that this relationship points to
 */
public record BlockRelationship(
        String type,
        List<String> ids
) {
    /**
     * Creates a BlockRelationship from AWS SDK Relationship.
     */
    public static BlockRelationship from(software.amazon.awssdk.services.textract.model.Relationship awsRelationship) {
        if (awsRelationship == null) {
            return null;
        }
        return new BlockRelationship(
                awsRelationship.typeAsString(),
                awsRelationship.ids()
        );
    }
}
